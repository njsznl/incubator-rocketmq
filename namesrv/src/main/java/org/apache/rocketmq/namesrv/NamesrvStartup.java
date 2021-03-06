/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.netty.NettySystemConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamesrvStartup {
    // 用于保存、读取配置文件
    public static Properties properties = null;
    // 用于解析命令行输入参数
    public static CommandLine commandLine = null;

    public static void main(String[] args) {
        main0(args);
    }

    public static NamesrvController main0(String[] args) {
        // 设置版本号[rocketmq.remoting.version -> MQVersion.CURRENT_VERSION]
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

        /**
         * Netty接收缓冲区大小
         * 设置的是ChannelOption.SO_SNDBUF参数
         * 该参数对应于套接字选项中的SO_SNDBUF
         */
        if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_SNDBUF_SIZE)) {
            NettySystemConfig.socketSndbufSize = 4096;
        }

        /**
         * Netty发送缓冲区大小
         * 设置的是ChannelOption.SO_RCVBUF参数
         * 该参数对应于套接字选项中的SO_RCVBUF
         */
        if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_RCVBUF_SIZE)) {
            NettySystemConfig.socketRcvbufSize = 4096;
        }

        try {
            // FastJson版本冲突检测，已被注释
            //PackageConflictDetect.detectFastjson();

            /**
             * 构造org.apache.commons.cli.Options
             * 并且添加-h -n参数
             * -h 打印帮助信息
             * -h 指定Name Server地址
             */
            Options options = ServerUtil.buildCommandlineOptions(new Options());

            /**
             * 初始化成员变量commandLine
             * 并且在Options中添加-c -p参数
             * -c 指定Name Server配置文件
             * -p 打印配置信息
             */
            commandLine =
                ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options),
                    new PosixParser());
            if (null == commandLine) {
                System.exit(-1);
                return null;
            }

            // 初始化NamesrvConfig和NettyServerConfig
            final NamesrvConfig namesrvConfig = new NamesrvConfig();
            final NettyServerConfig nettyServerConfig = new NettyServerConfig();
            // Name Server的端口定为9876
            nettyServerConfig.setListenPort(9876);

            /**
             * 如果命令带有-c参数，则读取文件内容，转换成全局Properties
             * 通过反射，将Properties中的值赋值给NamesrvConfig、NettyServerConfig
             */
            if (commandLine.hasOption('c')) {
                String file = commandLine.getOptionValue('c');
                if (file != null) {
                    InputStream in = new BufferedInputStream(new FileInputStream(file));
                    properties = new Properties();
                    properties.load(in);
                    MixAll.properties2Object(properties, namesrvConfig);
                    MixAll.properties2Object(properties, nettyServerConfig);

                    namesrvConfig.setConfigStorePath(file);

                    System.out.printf("load config properties file OK, " + file + "%n");
                    in.close();
                }
            }

            // 如果命令带有-p参数，则打印出NamesrvConfig、NettyServerConfig的属性
            if (commandLine.hasOption('p')) {
                MixAll.printObjectProperties(null, namesrvConfig);
                MixAll.printObjectProperties(null, nettyServerConfig);
                System.exit(0);
            }

            /**
             * 解析命令行参数，并加载到namesrvConfig配置中
             * 通过debug发现，这一行在这里没用
             * commandLine2Properties()方法中将参数全名和属性值转换成Properties
             * 目前支持的参数的全名为configFile、help、namesrvAddr、printConfigItem
             * 但是NamesrvConfig类中没有与之对应的set方法，所以不知道意义何在
             */
            MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);

            // 判断ROCKETMQ_HOME不能为空
            if (null == namesrvConfig.getRocketmqHome()) {
                System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
                    + " variable in your environment to match the location of the RocketMQ installation%n");
                System.exit(-2);
            }

            /**
             * 初始化Logback日志工厂
             * RocketMQ默认使用Logback作为日志输出
             */
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(lc);
            lc.reset();
            configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");
            final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

            MixAll.printObjectProperties(log, namesrvConfig);
            MixAll.printObjectProperties(log, nettyServerConfig);

            /**
             * 初始化NamesrvController
             * 该类是Name Server的主要控制类
             */
            final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

            /**
             * remember all configs to prevent discard
             * 将全局Properties的内容复制到NamesrvController.Configuration.allConfigs中
             */
            controller.getConfiguration().registerConfig(properties);

            // 初始化NamesrvController
            boolean initResult = controller.initialize();
            if (!initResult) {
                controller.shutdown();
                System.exit(-3);
            }

            // 注册ShutdownHook
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                private volatile boolean hasShutdown = false;
                private AtomicInteger shutdownTimes = new AtomicInteger(0);

                @Override
                public void run() {
                    synchronized (this) {
                        log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet());
                        if (!this.hasShutdown) {
                            this.hasShutdown = true;
                            long begineTime = System.currentTimeMillis();
                            controller.shutdown();
                            long consumingTimeTotal = System.currentTimeMillis() - begineTime;
                            log.info("shutdown hook over, consuming time total(ms): " + consumingTimeTotal);
                        }
                    }
                }
            }, "ShutdownHook"));

            // 启动Netty服务
            controller.start();

            String tip = "The Name Server boot success. serializeType=" +
                    RemotingCommand.getSerializeTypeConfigInThisServer();
            log.info(tip);
            System.out.printf(tip + "%n");

            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config item");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }
}
