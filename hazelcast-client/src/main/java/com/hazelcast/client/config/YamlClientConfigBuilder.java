/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.config;

import com.hazelcast.config.AbstractYamlConfigBuilder;
import com.hazelcast.config.ConfigLoader;
import com.hazelcast.config.InvalidConfigurationException;
import com.hazelcast.internal.yaml.YamlLoader;
import com.hazelcast.internal.yaml.YamlMapping;
import com.hazelcast.internal.yaml.YamlNode;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.util.ExceptionUtil;
import org.w3c.dom.Node;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import static com.hazelcast.config.yaml.W3cDomUtil.asW3cNode;

/**
 * Loads the {@link com.hazelcast.client.config.ClientConfig} using YAML.
 */
public class YamlClientConfigBuilder extends AbstractYamlConfigBuilder {

    private static final ILogger LOGGER = Logger.getLogger(YamlClientConfigBuilder.class);

    private final InputStream in;

    public YamlClientConfigBuilder(String resource) throws IOException {
        URL url = ConfigLoader.locateConfig(resource);
        if (url == null) {
            throw new IllegalArgumentException("Could not load " + resource);
        }
        this.in = url.openStream();
    }

    public YamlClientConfigBuilder(File file) throws IOException {
        if (file == null) {
            throw new NullPointerException("File is null!");
        }
        this.in = new FileInputStream(file);
    }

    public YamlClientConfigBuilder(URL url) throws IOException {
        if (url == null) {
            throw new NullPointerException("URL is null!");
        }
        this.in = url.openStream();
    }

    public YamlClientConfigBuilder(InputStream in) {
        this.in = in;
    }

    /**
     * Loads the client config using the following resolution mechanism:
     * <ol>
     * <li>first it checks if a system property 'hazelcast.client.config' is set. If it exist and it begins with
     * 'classpath:', then a classpath resource is loaded. Else it will assume it is a file reference</li>
     * <li>it checks if a hazelcast-client.Yaml is available in the working dir</li>
     * <li>it checks if a hazelcast-client.Yaml is available on the classpath</li>
     * <li>it loads the hazelcast-client-default.Yaml</li>
     * </ol>
     */
    public YamlClientConfigBuilder() {
        this((YamlClientConfigLocator) null);
    }

    /**
     * Constructs a {@link YamlClientConfigBuilder} that loads the configuration
     * with the provided {@link YamlClientConfigLocator}.
     * <p/>
     * If the provided {@link YamlClientConfigLocator} is {@code null}, a new
     * instance is created and the config is located in every possible
     * places. For these places, please see {@link YamlClientConfigLocator}.
     * <p/>
     * If the provided {@link YamlClientConfigLocator} is not {@code null}, it
     * is expected that it already located the configuration YAML to load
     * from. No further attempt to locate the configuration YAML is made
     * if the configuration YAML is not located already.
     *
     * @param locator the configured locator to use
     */
    public YamlClientConfigBuilder(YamlClientConfigLocator locator) {
        if (locator == null) {
            locator = new YamlClientConfigLocator();
            locator.locateEverywhere();
        }

        this.in = locator.getIn();
    }

    public ClientConfig build() {
        return build(Thread.currentThread().getContextClassLoader());
    }

    public ClientConfig build(ClassLoader classLoader) {
        ClientConfig clientConfig = new ClientConfig();
        build(clientConfig, classLoader);
        return clientConfig;
    }

    public void setProperties(Properties properties) {
        setPropertiesInternal(properties);
    }

    void build(ClientConfig clientConfig, ClassLoader classLoader) {
        clientConfig.setClassLoader(classLoader);
        try {
            parseAndBuildConfig(clientConfig);
        } catch (Exception e) {
            throw ExceptionUtil.rethrow(e);
        } finally {
            IOUtil.closeResource(in);
        }
    }

    private void parseAndBuildConfig(ClientConfig config) throws Exception {
        YamlMapping yamlRootNode;
        try {
            yamlRootNode = ((YamlMapping) YamlLoader.load(in));
        } catch (Exception ex) {
            throw new InvalidConfigurationException("Invalid YAML configuration", ex);
        }

        YamlNode clientRoot = yamlRootNode.childAsMapping(ClientConfigSections.HAZELCAST_CLIENT.name);
        if (clientRoot == null) {
            throw new InvalidConfigurationException("No mapping with hazelcast-client key is found in the provided "
                    + "configuration");
        }

        Node w3cRootNode = asW3cNode(clientRoot);
        replaceVariables(w3cRootNode);
        importDocuments(clientRoot);

        new YamlClientDomConfigProcessor(true, config).buildConfig(w3cRootNode);
    }

    @Override
    protected String getConfigRoot() {
        return ClientConfigSections.HAZELCAST_CLIENT.name;
    }
}
