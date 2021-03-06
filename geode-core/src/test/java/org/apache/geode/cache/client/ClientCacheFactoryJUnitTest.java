/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.cache.client;

import static org.apache.geode.distributed.ConfigurationProperties.CACHE_XML_FILE;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.LOG_LEVEL;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.runners.MethodSorters.NAME_ASCENDING;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.jgroups.util.UUID;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.RegionService;
import org.apache.geode.cache.client.internal.ProxyCache;
import org.apache.geode.cache.client.internal.UserAttributes;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.distributed.internal.membership.gms.GMSMember;
import org.apache.geode.internal.HeapDataOutputStream;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.VersionedDataInputStream;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.pdx.ReflectionBasedAutoSerializer;
import org.apache.geode.test.junit.categories.ClientServerTest;
import org.apache.geode.test.junit.categories.IntegrationTest;

/**
 * Unit test for the ClientCacheFactory class
 *
 * @since GemFire 6.5
 */
@FixMethodOrder(NAME_ASCENDING)
@Category({IntegrationTest.class, ClientServerTest.class})
public class ClientCacheFactoryJUnitTest {

  private ClientCache clientCache;
  private File tmpFile;

  @After
  public void tearDown() throws Exception {
    if (this.clientCache != null && !this.clientCache.isClosed()) {
      clientCache.close();
    }
    if (tmpFile != null && tmpFile.exists()) {
      tmpFile.delete();
    }
  }

  @AfterClass
  public static void afterClass() {
    InternalDistributedSystem ids = InternalDistributedSystem.getAnyInstance();
    if (ids != null) {
      ids.disconnect();
    }
  }

  @Test
  public void test000Defaults() throws Exception {
    this.clientCache = new ClientCacheFactory().create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    Properties dsProps = this.clientCache.getDistributedSystem().getProperties();
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    Pool defPool = gfc.getDefaultPool();
    assertEquals("DEFAULT", defPool.getName());
    assertEquals(new ArrayList(), defPool.getLocators());
    assertEquals(
        Collections.singletonList(
            new InetSocketAddress(InetAddress.getLocalHost(), CacheServer.DEFAULT_PORT)),
        defPool.getServers());
    assertEquals(PoolFactory.DEFAULT_SOCKET_CONNECT_TIMEOUT, defPool.getSocketConnectTimeout());

    ClientCache cc2 = new ClientCacheFactory().create();
    if (cc2 != this.clientCache) {
      fail("expected cc2 and cc to be == " + cc2 + this.clientCache);
    }

    try {
      new ClientCacheFactory().set(LOG_LEVEL, "severe").create();
      fail("expected create to fail");
    } catch (IllegalStateException expected) {
    }

    try {
      new ClientCacheFactory().addPoolLocator("127.0.0.1", 36666).create();
      fail("expected create to fail");
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void test001FindDefaultFromXML() throws Exception {
    this.tmpFile = File.createTempFile("ClientCacheFactoryJUnitTest", ".xml");
    this.tmpFile.deleteOnExit();
    URL url = ClientCacheFactoryJUnitTest.class
        .getResource("ClientCacheFactoryJUnitTest_single_pool.xml");;
    FileUtils.copyFile(new File(url.getFile()), this.tmpFile);
    this.clientCache =
        new ClientCacheFactory().set(CACHE_XML_FILE, this.tmpFile.getAbsolutePath()).create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    Properties dsProps = this.clientCache.getDistributedSystem().getProperties();
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    Pool defPool = gfc.getDefaultPool();
    assertEquals("my_pool_name", defPool.getName());
    assertEquals(new ArrayList(), defPool.getLocators());
    assertEquals(
        Collections.singletonList(new InetSocketAddress("localhost", CacheServer.DEFAULT_PORT)),
        defPool.getServers());
    assertEquals(PoolFactory.DEFAULT_SOCKET_CONNECT_TIMEOUT, defPool.getSocketConnectTimeout());
  }

  /**
   * Make sure if we have a single pool that it will be used as the default
   */
  @Test
  public void test002DPsinglePool() throws Exception {
    Properties dsProps = new Properties();
    dsProps.setProperty(MCAST_PORT, "0");
    DistributedSystem ds = DistributedSystem.connect(dsProps);
    Pool p = PoolManager.createFactory().addServer(InetAddress.getLocalHost().getHostName(), 7777)
        .setSocketConnectTimeout(1400).create("singlePool");
    this.clientCache = new ClientCacheFactory().create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    Pool defPool = gfc.getDefaultPool();
    assertEquals(p, defPool);
    assertEquals(1400, defPool.getSocketConnectTimeout());

    // make sure if we can not create a secure user cache when one pool
    // exists that is not multiuser enabled
    try {
      Properties suProps = new Properties();
      suProps.setProperty("user", "foo");
      RegionService cc = this.clientCache.createAuthenticatedView(suProps);
      fail("expected IllegalStateException");
    } catch (IllegalStateException ignore) {
    }
    // however we should be to to create it by configuring a pool
    {
      Properties suProps = new Properties();
      suProps.setProperty("user", "foo");

      Pool pool = PoolManager.createFactory()
          .addServer(InetAddress.getLocalHost().getHostName(), CacheServer.DEFAULT_PORT)
          .setMultiuserAuthentication(true).setSocketConnectTimeout(2345).create("pool1");
      RegionService cc = this.clientCache.createAuthenticatedView(suProps, pool.getName());
      ProxyCache pc = (ProxyCache) cc;
      UserAttributes ua = pc.getUserAttributes();
      Pool proxyDefPool = ua.getPool();
      assertEquals(
          Collections.singletonList(
              new InetSocketAddress(InetAddress.getLocalHost(), CacheServer.DEFAULT_PORT)),
          proxyDefPool.getServers());
      assertEquals(true, proxyDefPool.getMultiuserAuthentication());
      assertEquals(2345, proxyDefPool.getSocketConnectTimeout());
    }
  }

  /**
   * Make sure if we have more than one pool that we do not have a default
   */
  @Test
  public void test003DPmultiplePool() throws Exception {
    Properties dsProps = new Properties();
    dsProps.setProperty(MCAST_PORT, "0");
    DistributedSystem ds = DistributedSystem.connect(dsProps);
    PoolManager.createFactory().addServer(InetAddress.getLocalHost().getHostName(), 7777)
        .setSocketConnectTimeout(2500).create("p7");
    PoolManager.createFactory().addServer(InetAddress.getLocalHost().getHostName(), 6666)
        .setSocketConnectTimeout(5200).create("p6");
    this.clientCache = new ClientCacheFactory().create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    Pool defPool = gfc.getDefaultPool();
    assertEquals(null, defPool);
    assertEquals(2500, PoolManager.find("p7").getSocketConnectTimeout());
    assertEquals(5200, PoolManager.find("p6").getSocketConnectTimeout());

    // make sure if we can not create a secure user cache when more than one pool
    // exists that is not multiuser enabled
    try {
      Properties suProps = new Properties();
      suProps.setProperty("user", "foo");
      RegionService cc = this.clientCache.createAuthenticatedView(suProps);
      fail("expected IllegalStateException");
    } catch (IllegalStateException ignore) {
    }
    // however we should be to to create it by configuring a pool
    {
      Properties suProps = new Properties();
      suProps.setProperty("user", "foo");
      Pool pool = PoolManager.createFactory()
          .addServer(InetAddress.getLocalHost().getHostName(), CacheServer.DEFAULT_PORT)
          .setMultiuserAuthentication(true).create("pool1");
      RegionService cc = this.clientCache.createAuthenticatedView(suProps, pool.getName());
      ProxyCache pc = (ProxyCache) cc;
      UserAttributes ua = pc.getUserAttributes();
      Pool proxyDefPool = ua.getPool();
      assertEquals(
          Collections.singletonList(
              new InetSocketAddress(InetAddress.getLocalHost(), CacheServer.DEFAULT_PORT)),
          proxyDefPool.getServers());
      assertEquals(true, proxyDefPool.getMultiuserAuthentication());
      assertEquals(PoolFactory.DEFAULT_SOCKET_CONNECT_TIMEOUT,
          proxyDefPool.getSocketConnectTimeout());
    }
  }

  @Test
  public void test004SetMethod() throws Exception {
    this.clientCache =
        new ClientCacheFactory().set(LOG_LEVEL, "severe").setPoolSocketConnectTimeout(0).create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    Properties dsProps = this.clientCache.getDistributedSystem().getProperties();
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    assertEquals("severe", dsProps.getProperty(LOG_LEVEL));
    assertEquals(0, this.clientCache.getDefaultPool().getSocketConnectTimeout());

    try {
      new ClientCacheFactory().setPoolSocketConnectTimeout(-1).create();
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException ignore) {
    }
  }

  @Test
  public void test005SecureUserDefaults() throws Exception {
    Properties suProps = new Properties();
    suProps.setProperty("user", "foo");
    GemFireCacheImpl gfc =
        (GemFireCacheImpl) new ClientCacheFactory().setPoolMultiuserAuthentication(true).create();
    this.clientCache = gfc;
    RegionService cc1 = this.clientCache.createAuthenticatedView(suProps);

    assertEquals(true, gfc.isClient());
    Properties dsProps = this.clientCache.getDistributedSystem().getProperties();
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    Pool defPool = gfc.getDefaultPool();
    assertEquals("DEFAULT", defPool.getName());
    assertEquals(new ArrayList(), defPool.getLocators());
    assertEquals(
        Collections.singletonList(
            new InetSocketAddress(InetAddress.getLocalHost(), CacheServer.DEFAULT_PORT)),
        defPool.getServers());
    assertEquals(true, defPool.getMultiuserAuthentication());

    // make sure we can create another secure user cache
    RegionService cc2 = this.clientCache.createAuthenticatedView(suProps);
    assertEquals(true, gfc.isClient());
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    defPool = gfc.getDefaultPool();
    assertEquals("DEFAULT", defPool.getName());
    assertEquals(new ArrayList(), defPool.getLocators());
    assertEquals(
        Collections.singletonList(
            new InetSocketAddress(InetAddress.getLocalHost(), CacheServer.DEFAULT_PORT)),
        defPool.getServers());
    assertEquals(true, defPool.getMultiuserAuthentication());
    if (cc1 == cc2) {
      fail("expected two different secure user caches");
    }
  }

  @Test
  public void test006NonDefaultPool() throws Exception {
    this.clientCache = new ClientCacheFactory()
        .addPoolServer(InetAddress.getLocalHost().getHostName(), 55555).create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    Properties dsProps = this.clientCache.getDistributedSystem().getProperties();
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    Pool defPool = gfc.getDefaultPool();
    assertEquals("DEFAULT", defPool.getName());
    assertEquals(new ArrayList(), defPool.getLocators());
    assertEquals(
        Collections.singletonList(new InetSocketAddress(InetAddress.getLocalHost(), 55555)),
        defPool.getServers());

    ClientCache cc2 = new ClientCacheFactory().create();
    gfc = (GemFireCacheImpl) this.clientCache;
    assertEquals(true, gfc.isClient());
    dsProps = this.clientCache.getDistributedSystem().getProperties();
    assertEquals("0", dsProps.getProperty(MCAST_PORT));
    assertEquals("", dsProps.getProperty(LOCATORS));
    defPool = gfc.getDefaultPool();
    assertEquals("DEFAULT", defPool.getName());
    assertEquals(new ArrayList(), defPool.getLocators());
    assertEquals(
        Collections.singletonList(new InetSocketAddress(InetAddress.getLocalHost(), 55555)),
        defPool.getServers());

    try {
      clientCache = new ClientCacheFactory()
          .addPoolServer(InetAddress.getLocalHost().getHostName(), 44444).create();
      fail("expected create to fail");
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void test007Bug44907() {
    new ClientCacheFactory().setPdxSerializer(new ReflectionBasedAutoSerializer()).create();
    clientCache =
        new ClientCacheFactory().setPdxSerializer(new ReflectionBasedAutoSerializer()).create();
  }

  @Test
  public void testDefaultPoolTimeoutMultiplier() throws Exception {
    clientCache = new ClientCacheFactory().setPoolSubscriptionTimeoutMultiplier(2)
        .addPoolServer(InetAddress.getLocalHost().getHostName(), 7777).create();
    Pool defaultPool = clientCache.getDefaultPool();
    assertEquals(2, defaultPool.getSubscriptionTimeoutMultiplier());
  }

  @Test
  public void testOldClientIDDeserialization() throws Exception {
    // during a HandShake a clientID is read w/o knowing the client's
    // version
    clientCache = new ClientCacheFactory().create();
    GemFireCacheImpl gfc = (GemFireCacheImpl) clientCache;
    InternalDistributedMember memberID =
        (InternalDistributedMember) clientCache.getDistributedSystem().getDistributedMember();
    GMSMember gmsID = (GMSMember) memberID.getNetMember();
    memberID.setVersionObjectForTest(Version.GFE_82);
    assertEquals(Version.GFE_82, memberID.getVersionObject());
    ClientProxyMembershipID clientID = ClientProxyMembershipID.getClientId(memberID);
    HeapDataOutputStream out = new HeapDataOutputStream(Version.GFE_82);
    DataSerializer.writeObject(clientID, out);

    DataInputStream in =
        new VersionedDataInputStream(new ByteArrayInputStream(out.toByteArray()), Version.CURRENT);
    ClientProxyMembershipID newID = DataSerializer.readObject(in);
    InternalDistributedMember newMemberID =
        (InternalDistributedMember) newID.getDistributedMember();
    assertEquals(Version.GFE_82, newMemberID.getVersionObject());
    assertEquals(Version.GFE_82, newID.getClientVersion());
    GMSMember newGmsID = (GMSMember) newMemberID.getNetMember();
    assertEquals(0, newGmsID.getUuidLSBs());
    assertEquals(0, newGmsID.getUuidMSBs());

    gmsID.setUUID(new UUID(1234l, 5678l));
    memberID.setVersionObjectForTest(Version.CURRENT);
    clientID = ClientProxyMembershipID.getClientId(memberID);
    out = new HeapDataOutputStream(Version.CURRENT);
    DataSerializer.writeObject(clientID, out);

    in = new VersionedDataInputStream(new ByteArrayInputStream(out.toByteArray()), Version.CURRENT);
    newID = DataSerializer.readObject(in);
    newMemberID = (InternalDistributedMember) newID.getDistributedMember();
    assertEquals(Version.CURRENT, newMemberID.getVersionObject());
    assertEquals(Version.CURRENT, newID.getClientVersion());
    newGmsID = (GMSMember) newMemberID.getNetMember();
    assertEquals(gmsID.getUuidLSBs(), newGmsID.getUuidLSBs());
    assertEquals(gmsID.getUuidMSBs(), newGmsID.getUuidMSBs());

  }
}
