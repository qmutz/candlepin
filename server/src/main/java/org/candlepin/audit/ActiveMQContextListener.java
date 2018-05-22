/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.audit;

import org.apache.activemq.artemis.api.core.BroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.BroadcastGroupConfiguration;
import org.apache.activemq.artemis.api.core.DiscoveryGroupConfiguration;
import org.apache.activemq.artemis.api.core.TransportConfigurationHelper;
import org.apache.activemq.artemis.api.core.UDPBroadcastEndpointFactory;
import org.apache.activemq.artemis.core.config.ClusterConnectionConfiguration;
import org.apache.activemq.artemis.core.config.DivertConfiguration;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptorFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.candlepin.async.JobMessageFactory;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.candlepin.async.JobMessageSource;
import org.candlepin.config.ConfigProperties;

import com.google.common.collect.Lists;
import com.google.inject.Injector;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnectorFactory;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.commons.io.FileUtils;

import org.candlepin.controller.QpidStatusMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;


/**
 * ActiveMQContextListener - Invoked from our core CandlepinContextListener, thus
 * doesn't actually implement ServletContextListener.
 */
public class ActiveMQContextListener {
    private static  Logger log = LoggerFactory.getLogger(ActiveMQContextListener.class);

    private EmbeddedActiveMQ activeMQServer;
    private EventSource eventSource;
    private JobMessageSource jobMessageSource;

    public void contextDestroyed() {
        if (activeMQServer != null) {
            eventSource.shutDown();
            jobMessageSource.shutDown();
            try {
                activeMQServer.stop();
                log.info("ActiveMQ server stopped.");
            }
            catch (Exception e) {
                log.error("Error stopping ActiveMQ server", e);
            }

        }
    }

    public void contextInitialized(Injector injector) {
        org.candlepin.common.config.Configuration candlepinConfig =
            injector.getInstance(org.candlepin.common.config.Configuration.class);

        List<EventListener> eventListeners = new ArrayList<>();
        getActiveMQListeners(candlepinConfig).forEach(listenerClass -> {
            try {
                Class<?> clazz = this.getClass().getClassLoader().loadClass(listenerClass);
                eventListeners.add((EventListener) injector.getInstance(clazz));
            }
            catch (Exception e) {
                log.warn("Unable to register listener {}", listenerClass, e);
            }
        });

        if (activeMQServer == null) {
            Configuration config = new ConfigurationImpl();

            HashSet<TransportConfiguration> transports =
                new HashSet<>();
            transports.add(new TransportConfiguration(InVMAcceptorFactory.class
                .getName()));
            config.setAcceptorConfigurations(transports);

            // alter the default pass to silence log output
            config.setClusterUser(null);
            config.setClusterPassword(null);

            // in vm, who needs security?
            config.setSecurityEnabled(false);

            config.setJournalType(JournalType.NIO);

            config.setCreateBindingsDir(true);
            config.setCreateJournalDir(true);

            String baseDir = candlepinConfig.getString(ConfigProperties.ACTIVEMQ_BASE_DIR);

            config.setBindingsDirectory(new File(baseDir, "bindings").toString());
            config.setJournalDirectory(new File(baseDir, "journal").toString());
            config.setLargeMessagesDirectory(new File(baseDir, "largemsgs").toString());
            config.setPagingDirectory(new File(baseDir, "paging").toString());

            // Build AddressSettings for the event handlers.
            Map<String, AddressSettings> addressSettings = new HashMap<>();
            addressSettings.putAll(buildAddressSettings(eventListeners, candlepinConfig));
            addressSettings.putAll(buildAsyncJobAddressSettings(candlepinConfig));

            config.setAddressesSettings(addressSettings);
            config.addDivertConfiguration(buildDivertConfig());

            int maxScheduledThreads = candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_MAX_SCHEDULED_THREADS);
            int maxThreads = candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_MAX_THREADS);
            if (maxThreads != -1) {
                config.setThreadPoolMaxSize(maxThreads);
            }

            if (maxScheduledThreads != -1) {
                config.setScheduledThreadPoolMaxSize(maxScheduledThreads);
            }

            /**
             * Anything up to size of LARGE_MSG_SIZE may be needed to be written to the Journal,
             * so we must set buffer size accordingly.
             *
             * If buffer size would be < LARGE_MSG_SIZE we may get exceptions such as this:
             * Can't write records bigger than the bufferSize(XXXYYY) on the journal
             */
            int largeMsgSize = candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_LARGE_MSG_SIZE);
            config.setJournalBufferSize_AIO(largeMsgSize);
            config.setJournalBufferSize_NIO(largeMsgSize);


            // Configure the server acceptors (how connections can be made).
//            config.addAcceptorConfiguration(new TransportConfiguration(InVMAcceptorFactory.class.getName()));

            Map<String, Object> nettyConfig = new HashMap<>();
            nettyConfig.put(TransportConstants.HOST_PROP_NAME, candlepinConfig.getString(ConfigProperties.ACTIVEMQ_CLUSTER_HOST));
            nettyConfig.put(TransportConstants.PORT_PROP_NAME, candlepinConfig.getString(ConfigProperties.ACTIVEMQ_CLUSTER_PORT));
            TransportConfiguration netty = new TransportConfiguration(NettyAcceptorFactory.class.getName(), nettyConfig, "async-acceptor");
            config.addAcceptorConfiguration(netty);

            config.addConnectorConfiguration("netty",
                new TransportConfiguration(NettyConnectorFactory.class.getName(), nettyConfig, "async-connector"));


            // Configure clustering

            UDPBroadcastEndpointFactory udpBroadcast = new UDPBroadcastEndpointFactory();
//            udpBroadcast.setLocalBindAddress("192.168.2.103");
//            udpBroadcast.setLocalBindPort(TransportConstants.DEFAULT_PORT);
            udpBroadcast.setGroupAddress(candlepinConfig.getString(ConfigProperties.ACTIVEMQ_CLUSTER_GROUP_ADDR));
            udpBroadcast.setGroupPort(candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_CLUSTER_GROUP_PORT));

            BroadcastGroupConfiguration broadcast = new BroadcastGroupConfiguration();
            broadcast.setName("async_jobs_broadcast");
            broadcast.setConnectorInfos(Arrays.asList("netty"));
            broadcast.setEndpointFactory(udpBroadcast);
            config.addBroadcastGroupConfiguration(broadcast);

            DiscoveryGroupConfiguration discovery = new DiscoveryGroupConfiguration();
            discovery.setName("async_jobs_discovery");
            discovery.setBroadcastEndpointFactory(udpBroadcast);
            config.addDiscoveryGroupConfiguration("async_jobs_discovery", discovery);

            ClusterConnectionConfiguration cluster = new ClusterConnectionConfiguration();
            cluster.setName("my-cluster");
            cluster.setMaxHops(1);
            cluster.setRetryInterval(500);
            cluster.setConnectorName("netty");
            cluster.setMessageLoadBalancingType(MessageLoadBalancingType.STRICT);
            cluster.setDiscoveryGroupName("async_jobs_discovery");
            config.addClusterConfiguration(cluster);

            activeMQServer = new EmbeddedActiveMQ();
            activeMQServer.setConfiguration(config);
        }
        try {
            activeMQServer.start();
            log.info("ActiveMQ server started");
        }
        catch (Exception e) {
            log.error("Failed to start ActiveMQ message server:", e);
            throw new RuntimeException(e);
        }

        cleanupOldQueues();

        // Create the event source and register all listeners now that the server is started
        // and the old queues are cleaned up.
        eventSource = injector.getInstance(EventSource.class);
        setupAmqp(injector, candlepinConfig, eventSource);

        for (EventListener listener : eventListeners) {
            try {
                eventSource.registerListener(listener);
            } catch (Exception e) {
                log.warn("Unable to register listener {}", listener, e);
            }
        }

        // Register configured Job message listeners.
        jobMessageSource = injector.getInstance(JobMessageSource.class);
        for (String jobListenerClass : getJobListeners(candlepinConfig)) {
            try {
                jobMessageSource.registerListener(jobListenerClass);
            }
            catch (ActiveMQException amqe) {
                log.warn("Unable to register job message listener {}.", jobListenerClass, amqe);
            }
        }

        // Initialize the Event sink AFTER the internal server has been
        // created and started.
        EventSink sink = injector.getInstance(EventSink.class);
        try {
            sink.initialize();
        }
        catch (Exception e) {
            log.error("Failed to initialize EventSink:", e);
            throw new RuntimeException(e);
        }

        // Initialize the JobMessageFactory AFTER the server has been started.
        JobMessageFactory factory = injector.getInstance(JobMessageFactory.class);
        try {
            factory.initialize();
        }
        catch (Exception e) {
            log.error("Unable to initialize JobMessageFactory. Async jobs willnot be started.", e);
            throw new RuntimeException(e);
        }
    }

    private void registerEventListener(Injector injector, String className) {
        try {
            Class<?> clazz = this.getClass().getClassLoader().loadClass(className);
            eventSource.registerListener((EventListener) injector.getInstance(clazz));
        }
        catch (Exception e) {
            log.warn("Unable to register listener {}.", className, e);
        }
    }

    private AddressSettings defaultAddressSettings(org.candlepin.common.config.Configuration candlepinConfig) {
        String addressPolicyString =
            candlepinConfig.getString(ConfigProperties.ACTIVEMQ_ADDRESS_FULL_POLICY);
        long maxQueueSizeInMb = candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_MAX_QUEUE_SIZE);
        long maxPageSizeInMb = candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_MAX_PAGE_SIZE);

        AddressFullMessagePolicy addressPolicy = null;
        if (addressPolicyString.equals("PAGE")) {
            addressPolicy = AddressFullMessagePolicy.PAGE;
        }
        else if (addressPolicyString.equals("BLOCK")) {
            addressPolicy = AddressFullMessagePolicy.BLOCK;
        }
        else {
            throw new IllegalArgumentException("Unknown ACTIVEMQ_ADDRESS_FULL_POLICY: " +
                                                   addressPolicyString + " . Please use one of: PAGE, BLOCK");
        }

        AddressSettings settings = new AddressSettings();
        // Paging sizes need to be converted to bytes
        settings.setMaxSizeBytes(maxQueueSizeInMb * FileUtils.ONE_MB);
        if (addressPolicy == AddressFullMessagePolicy.PAGE) {
            settings.setPageSizeBytes(maxPageSizeInMb * FileUtils.ONE_MB);
        }
        settings.setAddressFullMessagePolicy(addressPolicy);

        return settings;
    }

    private Map<String, AddressSettings> buildAsyncJobAddressSettings(org.candlepin.common.config.Configuration candlepinConfig) {
        Map<String, AddressSettings> settings = new HashMap<>();
        AddressSettings asyncJobsConfig = this.defaultAddressSettings(candlepinConfig);
        configureMessageRetry(asyncJobsConfig, candlepinConfig);
        // TODO Any async job queue specific settings for retry ect... Change filter and
        //      add new setting config per handler if required.
        settings.put(MessageAddress.QPID_ASYNC_JOB_MESSAGE_ADDRESS, asyncJobsConfig);
        return settings;
    }

    private Map<String, AddressSettings> buildAddressSettings(List<EventListener> eventListeners,
        org.candlepin.common.config.Configuration candlepinConfig) {
        Map<String, AddressSettings> settings = new HashMap<>();

        for (EventListener listener : eventListeners) {
            String address = MessageAddress.DEFAULT_EVENT_MESSAGE_ADDRESS;
            AddressSettings listenerAddressConfig = defaultAddressSettings(candlepinConfig);

            // Set the retry settings on the common address configuration.
            if (listener.requiresQpid()) {
                // When qpid is enabled we want the message to be set to be redelivered right away
                // so that it goes right back to the top of the queue. When there's an issue with
                // Qpid, the receiver will shut down the Consumer and the messages will remain in
                // order.
                listenerAddressConfig.setRedeliveryDelay(0);
                listenerAddressConfig.setMaxDeliveryAttempts(1);
                address = MessageAddress.QPID_EVENT_MESSAGE_ADDRESS;
            }
            else {
                // Message retry will be configured for anything other than the Qpid
                // listener and requires different settings.
                configureMessageRetry(listenerAddressConfig, candlepinConfig);
            }

            settings.put(address, listenerAddressConfig);
        }
        return settings;
    }

    private DivertConfiguration buildDivertConfig() {
        // Set up a divert to qpid queue. This allow us to send a single message that will
        // end up getting diverted to all queues plus the qpid queue. We do this to allow
        // the qpid address to have different settings without having to send a separate message
        // specifically to this queue.
        DivertConfiguration divertConfig = new DivertConfiguration();
        divertConfig.setName("QPID_DIVERT");
        divertConfig.setExclusive(false);
        divertConfig.setAddress(MessageAddress.DEFAULT_EVENT_MESSAGE_ADDRESS);
        divertConfig.setForwardingAddress(MessageAddress.QPID_EVENT_MESSAGE_ADDRESS);
        return divertConfig;
    }

    private void setupAmqp(Injector injector, org.candlepin.common.config.Configuration candlepinConfig,
        EventSource eventSource) {
        try {
            if (candlepinConfig.getBoolean(ConfigProperties.AMQP_INTEGRATION_ENABLED)) {
                // Listen for Qpid connection changes so that the appropriate ClientSessions
                // can be shutdown/restarted when Qpid status changes.
                QpidStatusMonitor qpidStatusMonitor = injector.getInstance(QpidStatusMonitor.class);
                qpidStatusMonitor.addStatusChangeListener(eventSource);

                // TODO Look into whether this connection is required. Qpid connection is NOT a singleton
                //      so I'm not sure that this connection is required as it isn't doing anything.
                //Both these classes should be singletons
                QpidConnection conFactory = injector.getInstance(QpidConnection.class);
                conFactory.connect();
            }
        }
        catch (Exception e) {
            log.error("Error starting AMQP client", e);
        }
    }

    /**
     * Configure message redelivery. We set the maximum number of times that a message should
     * be redelivered to 0 so that messages will remain in the queue and will never get sent
     * to the dead letter queue. Since candlepin does not currently set up, or use, a dead
     * letter queue, any messages sent there will be lost. We need to prevent this.
     *
     * @param addressSettings the AddressSetting to apply the retry settings to.
     * @param candlepinConfig the candlepin configuration to get the settings from.
     */
    private void configureMessageRetry(AddressSettings addressSettings,
        org.candlepin.common.config.Configuration candlepinConfig) {
        addressSettings.setRedeliveryDelay(
            candlepinConfig.getLong(ConfigProperties.ACTIVEMQ_REDELIVERY_DELAY));
        addressSettings.setMaxRedeliveryDelay(
            candlepinConfig.getLong(ConfigProperties.ACTIVEMQ_MAX_REDELIVERY_DELAY));
        addressSettings.setRedeliveryMultiplier(
            candlepinConfig.getLong(ConfigProperties.ACTIVEMQ_REDELIVERY_MULTIPLIER));
        addressSettings.setMaxDeliveryAttempts(
            candlepinConfig.getInt(ConfigProperties.ACTIVEMQ_MAX_DELIVERY_ATTEMPTS));
    }

    /**
     * @param candlepinConfig
     * @return List of class names that will be configured as ActiveMQ listeners.
     */
    public static List<String> getActiveMQListeners(
        org.candlepin.common.config.Configuration candlepinConfig) {
        //AMQP integration here - If it is disabled, don't add it to listeners.
        List<String> listeners = Lists.newArrayList(
            candlepinConfig.getList(ConfigProperties.AUDIT_LISTENERS));

        if (candlepinConfig
            .getBoolean(ConfigProperties.AMQP_INTEGRATION_ENABLED)) {
            listeners.add(AMQPBusPublisher.class.getName());
        }
        return listeners;
    }

    public static List<String> getJobListeners(org.candlepin.common.config.Configuration candlepinConfig) {
        return candlepinConfig.getList(ConfigProperties.ALLOWED_ASYNC_JOBS, Collections.EMPTY_LIST);
    }

    /**
     * Remove any old message queues that have a 0 message count in them.
     * This lets us not worry about changing around the registered listeners.
     */
    private void cleanupOldQueues() {
        log.debug("Cleaning old message queues");
        try {
            String [] queues = activeMQServer.getActiveMQServer().getActiveMQServerControl().getQueueNames();

            ServerLocator locator = ActiveMQClient.createServerLocatorWithoutHA(
                new TransportConfiguration(InVMConnectorFactory.class.getName()));

            ClientSessionFactory factory =  locator.createSessionFactory();
            ClientSession session = factory.createSession(true, true);
            session.start();

            for (int i = 0; i < queues.length; i++) {
                long msgCount =
                    session.queueQuery(new SimpleString(queues[i])).getMessageCount();
                if (msgCount == 0) {
                    log.debug(String.format("found queue '%s' with 0 messages. deleting",
                        queues[i]));
                    session.deleteQueue(queues[i]);
                }
                else {
                    log.debug(String.format("found queue '%s' with %d messages. kept",
                        queues[i], msgCount));
                }
            }

            session.stop();
            session.close();
        }
        catch (Exception e) {
            log.error("Problem cleaning old message queues:", e);
            throw new RuntimeException("Problem cleaning message queue", e);
        }
    }
}
