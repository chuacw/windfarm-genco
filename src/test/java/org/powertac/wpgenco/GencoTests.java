/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.powertac.wpgenco;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.TreeMap;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powertac.common.Competition;
import org.powertac.common.MarketPosition;
import org.powertac.common.PluginConfig;
import org.powertac.common.RandomSeed;
import org.powertac.common.Order;
import org.powertac.common.TimeService;
import org.powertac.common.Timeslot;
import org.powertac.common.config.Configurator;
import org.powertac.common.interfaces.BrokerProxy;
import org.powertac.common.interfaces.ServerConfiguration;
import org.powertac.common.repo.RandomSeedRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.wpgenco.WindfarmGenco;

/**
 * Tests for the WindfarmGenco broker type
 * @author John Collins
 */
//@RunWith(SpringJUnit4ClassRunner.class)
//@ContextConfiguration(locations = {"classpath:test-config.xml"})
public class GencoTests
{
  //@Autowired
  private BrokerProxy mockProxy;
  
  //@Autowired
  private TimeslotRepo timeslotRepo;

  private WindfarmGenco windfarmGenco;
  private Instant start;
  private RandomSeedRepo mockSeedRepo;
  private RandomSeed seed;
  private ServerConfiguration serverConfig;
  private Configurator config;
  
  @Before
  public void setUp () throws Exception
  {
    Competition.newInstance("WindfarmGenco test");
    mockProxy = mock(BrokerProxy.class);
    mockSeedRepo = mock(RandomSeedRepo.class);
    seed = mock(RandomSeed.class);
    when(mockSeedRepo.getRandomSeed(eq(WindfarmGenco.class.getName()),
                                    anyInt(),
                                    anyString())).thenReturn(seed);
    timeslotRepo = new TimeslotRepo();
    windfarmGenco = new WindfarmGenco("Test");
    windfarmGenco.init(mockProxy, mockSeedRepo);
    start = new DateTime(2011, 1, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant();

    // Set up serverProperties mock
    serverConfig = mock(ServerConfiguration.class);
    config = new Configurator();
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock invocation) {
        Object[] args = invocation.getArguments();
        config.configureSingleton(args[0]);
        return null;
      }
    }).when(serverConfig).configureMe(anyObject());
  }

  @Test
  public void testGenco ()
  {
    assertNotNull("created something", windfarmGenco);
    assertEquals("correct name", "Test", windfarmGenco.getUsername());
  }
  
  @Test
  public void testInit()
  {
    // it has already had init() called, should have requested a seed
    verify(mockSeedRepo).getRandomSeed(eq(WindfarmGenco.class.getName()),
                                       anyInt(), eq("update"));
  }

  @Test
  public void testUpdateModel ()
  {
    when(seed.nextDouble()).thenReturn(0.5);
    assertEquals("correct initial capacity",
                 100.0, windfarmGenco.getCurrentCapacity(), 1e-6);
    assertTrue("initially in operation", windfarmGenco.isInOperation());
    windfarmGenco.updateModel(start);
    assertEquals("correct updated capacity",
                 100.0, windfarmGenco.getCurrentCapacity(), 1e-6);
    assertTrue("still in operation", windfarmGenco.isInOperation());
  }

  @Test
  public void testGenerateOrders ()
  {
    // set up the windfarmGenco
    // capture orders
    final ArrayList<Order> orderList = new ArrayList<Order>(); 
    doAnswer(new Answer() {
      public Object answer(InvocationOnMock invocation) {
        Object[] args = invocation.getArguments();
        orderList.add((Order)args[0]);
        return null;
      }
    }).when(mockProxy).routeMessage(isA(Order.class));
    // set up some timeslots
    Timeslot ts1 = timeslotRepo.makeTimeslot(start);
    ts1.disable();
    Timeslot ts2 = timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR));
    Timeslot ts3 = timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 2));
    assertEquals("2 enabled timeslots", 2, timeslotRepo.enabledTimeslots().size());
    // 50 mwh already sold in ts2
    MarketPosition posn2 = new MarketPosition(windfarmGenco, ts2, -50.0);
    windfarmGenco.addMarketPosition(posn2, ts2);
    // generate orders and check
    windfarmGenco.generateOrders(start, timeslotRepo.enabledTimeslots());
    assertEquals("two orders", 2, orderList.size());
    Order first = orderList.get(0);
    assertEquals("first order for ts2", ts2, first.getTimeslot());
    assertEquals("first order price", 1.0, first.getLimitPrice(), 1e-6);
    assertEquals("first order for 50 mwh", -50.0, first.getMWh(), 1e-6);
    Order second = orderList.get(1);
    assertEquals("second order for ts3", ts3, second.getTimeslot());
    assertEquals("second order price", 1.0, second.getLimitPrice(), 1e-6);
    assertEquals("second order for 100 mwh", -100.0, second.getMWh(), 1e-6);
  }

  // set commitment leadtime to a larger number and make sure ordering
  // behavior is correct
  @Test
  public void testGenerateOrders2 ()
  {
    // set up the windfarmGenco with commitment leadtime=3
    TreeMap<String, String> map = new TreeMap<String, String>();
    map.put("windfarmGenco.genco.commitmentLeadtime", "3");
    Configuration mapConfig = new MapConfiguration(map);
    config.setConfiguration(mapConfig);
    serverConfig.configureMe(windfarmGenco);
    // capture orders
    final ArrayList<Order> orderList = new ArrayList<Order>(); 
    doAnswer(new Answer() {
      public Object answer(InvocationOnMock invocation) {
        Object[] args = invocation.getArguments();
        orderList.add((Order)args[0]);
        return null;
      }
    }).when(mockProxy).routeMessage(isA(Order.class));
    // set up some timeslots
    Timeslot ts0 = timeslotRepo.makeTimeslot(start);
    ts0.disable();
    assertEquals("first ts has sn=0", 0, ts0.getSerialNumber());
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR));
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 2));
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 3));
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 4));
    assertEquals("4 enabled timeslots", 4, timeslotRepo.enabledTimeslots().size());

    // generate orders and check
    windfarmGenco.generateOrders(start, timeslotRepo.enabledTimeslots());
    assertEquals("two orders", 2, orderList.size());
    Order first = orderList.get(0);
    assertEquals("first order for ts3", 3, first.getTimeslot().getSerialNumber());
    assertEquals("first order price", 1.0, first.getLimitPrice(), 1e-6);
    assertEquals("first order for 100 mwh", -100.0, first.getMWh(), 1e-6);
    Order second = orderList.get(1);
    assertEquals("second order for ts4", 4, second.getTimeslot().getSerialNumber());
    assertEquals("second order price", 1.0, second.getLimitPrice(), 1e-6);
    assertEquals("second order for 100 mwh", -100.0, second.getMWh(), 1e-6);
  }

  // set commitment leadtime & market position and make sure ordering
  // behavior is correct
  @Test
  public void testGenerateOrders3 ()
  {
    // set up the windfarmGenco with commitment leadtime=3
    TreeMap<String, String> map = new TreeMap<String, String>();
    map.put("windfarmGenco.genco.commitmentLeadtime", "3");
    Configuration mapConfig = new MapConfiguration(map);
    config.setConfiguration(mapConfig);
    serverConfig.configureMe(windfarmGenco);
    // capture orders
    final ArrayList<Order> orderList = new ArrayList<Order>(); 
    doAnswer(new Answer() {
      public Object answer(InvocationOnMock invocation) {
        Object[] args = invocation.getArguments();
        orderList.add((Order)args[0]);
        return null;
      }
    }).when(mockProxy).routeMessage(isA(Order.class));
    // set up some timeslots
    Timeslot ts0 = timeslotRepo.makeTimeslot(start);
    ts0.disable();
    assertEquals("first ts has sn=0", 0, ts0.getSerialNumber());
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR));
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 2));
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 3));
    timeslotRepo.makeTimeslot(start.plus(TimeService.HOUR * 4));
    assertEquals("4 enabled timeslots", 4, timeslotRepo.enabledTimeslots().size());

    // 50 mwh already sold in ts2
    Timeslot ts2 = timeslotRepo.findBySerialNumber(2);
    MarketPosition posn2 = new MarketPosition(windfarmGenco, ts2, -50.0);
    windfarmGenco.addMarketPosition(posn2, ts2);

    // generate orders and check
    windfarmGenco.generateOrders(start, timeslotRepo.enabledTimeslots());
    assertEquals("two orders", 3, orderList.size());
    Order order = orderList.get(0);
    assertEquals("second order for ts2", 2, order.getTimeslot().getSerialNumber());
    assertEquals("second order price", 1.0, order.getLimitPrice(), 1e-6);
    assertEquals("second order for 50 mwh", -50.0, order.getMWh(), 1e-6);
    order = orderList.get(1);
    assertEquals("second order for ts3", 3, order.getTimeslot().getSerialNumber());
    assertEquals("second order price", 1.0, order.getLimitPrice(), 1e-6);
    assertEquals("second order for 100 mwh", -100.0, order.getMWh(), 1e-6);
    order = orderList.get(2);
    assertEquals("third order for ts4", 4, order.getTimeslot().getSerialNumber());
    assertEquals("third order price", 1.0, order.getLimitPrice(), 1e-6);
    assertEquals("third order for 100 mwh", -100.0, order.getMWh(), 1e-6);
  }
}
