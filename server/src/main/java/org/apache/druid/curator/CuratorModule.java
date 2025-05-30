/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.curator;

import com.google.common.base.Strings;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import org.apache.curator.RetryPolicy;
import org.apache.curator.ensemble.fixed.FixedEnsembleProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.imps.DefaultACLProvider;
import org.apache.curator.retry.BoundedExponentialBackoffRetry;
import org.apache.druid.concurrent.Threads;
import org.apache.druid.guice.JsonConfigProvider;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.lifecycle.Lifecycle;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.service.AlertBuilder;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.server.metrics.MetricsModule;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CuratorModule implements Module
{
  private static final Logger log = new Logger(CuratorModule.class);

  static final int BASE_SLEEP_TIME_MS = 1000;
  static final int MAX_SLEEP_TIME_MS = 45000;

  private final boolean haltOnFailedStart;

  public CuratorModule()
  {
    this(true);
  }

  /**
   * @param haltOnFailedStart set to true if the JVM needs to be halted within 30 seconds of failed initialization
   *                          due to unhandled curator exceptions.
   */
  public CuratorModule(boolean haltOnFailedStart)
  {
    this.haltOnFailedStart = haltOnFailedStart;
  }

  @Override
  public void configure(Binder binder)
  {
    JsonConfigProvider.bind(binder, CuratorConfig.CONFIG_PREFIX, ZkEnablementConfig.class);
    JsonConfigProvider.bind(binder, CuratorConfig.CONFIG_PREFIX, CuratorConfig.class);
    MetricsModule.register(binder, DruidConnectionStateListener.class);
  }

  /**
   * Create the Curator framework outside of Guice given the ZK config.
   * Primarily for tests.
   */
  public static CuratorFramework createCurator(CuratorConfig config)
  {
    final CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
    if (!Strings.isNullOrEmpty(config.getZkUser()) && !Strings.isNullOrEmpty(config.getZkPwd())) {
      builder.authorization(
          config.getAuthScheme(),
          StringUtils.format("%s:%s", config.getZkUser(), config.getZkPwd()).getBytes(StandardCharsets.UTF_8)
      );
    }

    final RetryPolicy retryPolicy =
        new BoundedExponentialBackoffRetry(BASE_SLEEP_TIME_MS, MAX_SLEEP_TIME_MS, config.getMaxZkRetries());

    return builder
        .ensembleProvider(new FixedEnsembleProvider(config.getZkHosts()))
        .sessionTimeoutMs(config.getZkSessionTimeoutMs())
        .connectionTimeoutMs(config.getZkConnectionTimeoutMs())
        .retryPolicy(retryPolicy)
        .compressionProvider(new PotentiallyGzippedCompressionProvider(config.getEnableCompression()))
        .aclProvider(config.getEnableAcl() ? new SecuredACLProvider() : new DefaultACLProvider())
        .build();
  }

  /**
   * Provide the Curator framework via Guice, integrated with the Druid lifecycle.
   */
  @Provides
  @LazySingleton
  public CuratorFramework makeCurator(
      final ZkEnablementConfig zkEnablementConfig,
      final CuratorConfig config,
      final DruidConnectionStateListener connectionStateListener,
      final ServiceEmitter emitter,
      final Lifecycle lifecycle
  )
  {
    if (!zkEnablementConfig.isEnabled()) {
      throw new RuntimeException("Zookeeper is disabled, cannot create CuratorFramework.");
    }

    final CuratorFramework framework = createCurator(config);

    framework.getConnectionStateListenable().addListener(connectionStateListener);
    addUnhandledErrorListener(framework, emitter, lifecycle);
    addLifecycleHandler(framework, lifecycle);

    return framework;
  }

  /**
   * Provide an instance of {@link DruidConnectionStateListener} for monitoring connection state.
   */
  @Provides
  @LazySingleton
  public DruidConnectionStateListener makeConnectionStateListener(final ServiceEmitter emitter)
  {
    return new DruidConnectionStateListener(emitter);
  }

  /**
   * Add unhandled error listener that shuts down the JVM.
   */
  private void addUnhandledErrorListener(
      final CuratorFramework framework,
      final ServiceEmitter emitter,
      final Lifecycle lifecycle
  )
  {
    framework.getUnhandledErrorListenable().addListener((message, e) -> {
      emitter.emit(AlertBuilder.create("Unhandled Curator error").addThrowable(e));
      log.error(e, "Unhandled error in Curator, stopping server.");

      if (haltOnFailedStart) {
        final long startTime = System.currentTimeMillis();
        final Thread halter = new Thread(
            () -> {
              try {
                Threads.sleepFor(30, TimeUnit.SECONDS);
              }
              catch (InterruptedException ignored) {

              }
              log.warn(
                  "Could not stop server within %,d millis after unhandled Curator error. Halting immediately.",
                  System.currentTimeMillis() - startTime
              );
              Runtime.getRuntime().halt(1);
            },
            "exiter-thread"
        );
        halter.setDaemon(true);
        halter.start();
      }

      shutdown(lifecycle);
    });
  }

  /**
   * Add unhandled error listener that shuts down the JVM.
   */
  private void addLifecycleHandler(final CuratorFramework framework, final Lifecycle lifecycle)
  {
    lifecycle.addHandler(
        new Lifecycle.Handler()
        {
          @Override
          public void start()
          {
            log.debug("Starting Curator");
            framework.start();
          }

          @Override
          public void stop()
          {
            log.debug("Stopping Curator");
            framework.close();
          }
        }
    );
  }

  private void shutdown(Lifecycle lifecycle)
  {
    //noinspection finally (not completing the 'finally' block normally is intentional)
    try {
      lifecycle.stop();
    }
    catch (Throwable t) {
      log.error(t, "Exception when stopping server after unhandled Curator error.");
    }
    finally {
      System.exit(1);
    }
  }

  private static class SecuredACLProvider implements ACLProvider
  {
    @Override
    public List<ACL> getDefaultAcl()
    {
      return ZooDefs.Ids.CREATOR_ALL_ACL;
    }

    @Override
    public List<ACL> getAclForPath(String path)
    {
      return ZooDefs.Ids.CREATOR_ALL_ACL;
    }
  }
}
