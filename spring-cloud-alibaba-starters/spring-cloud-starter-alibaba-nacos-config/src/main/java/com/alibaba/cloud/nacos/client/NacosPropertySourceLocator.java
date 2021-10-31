/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.nacos.client;

import java.util.List;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.cloud.nacos.NacosConfigProperties;
import com.alibaba.cloud.nacos.NacosPropertySourceRepository;
import com.alibaba.cloud.nacos.parser.NacosDataParserHandler;
import com.alibaba.cloud.nacos.refresh.NacosContextRefresher;
import com.alibaba.nacos.api.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * @author xiaojing
 * @author pbting
 */
@Order(0)
public class NacosPropertySourceLocator implements PropertySourceLocator {

	private static final Logger log = LoggerFactory
			.getLogger(NacosPropertySourceLocator.class);

	private static final String NACOS_PROPERTY_SOURCE_NAME = "NACOS";

	private static final String SEP1 = "-";

	private static final String DOT = ".";

	private NacosPropertySourceBuilder nacosPropertySourceBuilder;

	private NacosConfigProperties nacosConfigProperties;

	private NacosConfigManager nacosConfigManager;

	/**
	 * recommend to use
	 * {@link NacosPropertySourceLocator#NacosPropertySourceLocator(com.alibaba.cloud.nacos.NacosConfigManager)}.
	 * @param nacosConfigProperties nacosConfigProperties
	 */
	@Deprecated
	public NacosPropertySourceLocator(NacosConfigProperties nacosConfigProperties) {
		this.nacosConfigProperties = nacosConfigProperties;
	}

	public NacosPropertySourceLocator(NacosConfigManager nacosConfigManager) {
		this.nacosConfigManager = nacosConfigManager;
		this.nacosConfigProperties = nacosConfigManager.getNacosConfigProperties();
	}

	@Override
	public PropertySource<?> locate(Environment env) {
		nacosConfigProperties.setEnvironment(env); // 将Spring容器中的Environment设置到nacosConfigProperties
		ConfigService configService = nacosConfigManager.getConfigService();
		if (null == configService) {
			log.warn("no instance of config service found, can't load config from nacos");
			return null;
		}
		long timeout = nacosConfigProperties.getTimeout(); // 获取超时时间，默认3000
		nacosPropertySourceBuilder = new NacosPropertySourceBuilder(configService, timeout);
		String name = nacosConfigProperties.getName(); // spring.cloud.nacos.config.name配置的名称
		String dataIdPrefix = nacosConfigProperties.getPrefix(); // spring.cloud.nacos.config.prefix配置的值
		if (StringUtils.isEmpty(dataIdPrefix)) {
			dataIdPrefix = name;
		}
		if (StringUtils.isEmpty(dataIdPrefix)) {
			dataIdPrefix = env.getProperty("spring.application.name"); // 获取应用名称
		}
		CompositePropertySource composite = new CompositePropertySource(NACOS_PROPERTY_SOURCE_NAME); // 名称为NACOS
		loadSharedConfiguration(composite); // 加载共享的配置文件，对应配置spring.cloud.nacos.config.sharedConfigs
		loadExtConfiguration(composite); // 加载扩展的配置文件，对应配置spring.cloud.nacos.config.extensionConfigs
		// 加载当前应用的配置，加载顺序：1.文件名（微服务名称）；2.文件名.文件扩展名；3.文件名-profile.文件扩展名，但使用优先级是3>2>1
		loadApplicationConfiguration(composite, dataIdPrefix, nacosConfigProperties, env);
		return composite;
	}

	/**
	 * load shared configuration.
	 */
	private void loadSharedConfiguration(CompositePropertySource compositePropertySource) {
		List<NacosConfigProperties.Config> sharedConfigs = nacosConfigProperties.getSharedConfigs();
		if (!CollectionUtils.isEmpty(sharedConfigs)) {
			checkConfiguration(sharedConfigs, "shared-configs"); // 校验共享配置文件的dataId
			loadNacosConfiguration(compositePropertySource, sharedConfigs);
		}
	}

	/**
	 * load extensional configuration.
	 */
	private void loadExtConfiguration(CompositePropertySource compositePropertySource) {
		List<NacosConfigProperties.Config> extConfigs = nacosConfigProperties.getExtensionConfigs();
		if (!CollectionUtils.isEmpty(extConfigs)) {
			checkConfiguration(extConfigs, "extension-configs");
			loadNacosConfiguration(compositePropertySource, extConfigs);
		}
	}

	/**
	 * load configuration of application.
	 */
	private void loadApplicationConfiguration(CompositePropertySource compositePropertySource, String dataIdPrefix, NacosConfigProperties properties, Environment environment) {
		String fileExtension = properties.getFileExtension(); // 获取文件扩展名
		String nacosGroup = properties.getGroup();
		// load directly once by default：文件名（微服务名称）, 注意这里的isRefreshable都是传的true都支持动态刷新配置
		loadNacosDataIfPresent(compositePropertySource, dataIdPrefix, nacosGroup, fileExtension, true);
		// load with suffix, which have a higher priority than the default：文件名.文件扩展名
		loadNacosDataIfPresent(compositePropertySource, dataIdPrefix + DOT + fileExtension, nacosGroup, fileExtension, true);
		// Loaded with profile, which have a higher priority than the suffix：文件名-profile.文件扩展名
		for (String profile : environment.getActiveProfiles()) {
			String dataId = dataIdPrefix + SEP1 + profile + DOT + fileExtension;
			loadNacosDataIfPresent(compositePropertySource, dataId, nacosGroup, fileExtension, true);
		}

	}

	private void loadNacosConfiguration(final CompositePropertySource composite, List<NacosConfigProperties.Config> configs) {
		for (NacosConfigProperties.Config config : configs) { // 若存在多个则遍历加载
			loadNacosDataIfPresent(composite, config.getDataId(), config.getGroup(), NacosDataParserHandler.getInstance().getFileExtension(config.getDataId()), config.isRefresh());
		}
	}

	private void checkConfiguration(List<NacosConfigProperties.Config> configs, String tips) {
		for (int i = 0; i < configs.size(); i++) {
			String dataId = configs.get(i).getDataId();
			if (dataId == null || dataId.trim().length() == 0) {
				throw new IllegalStateException(String.format("the [ spring.cloud.nacos.config.%s[%s] ] must give a dataId", tips, i));
			}
		}
	}

	private void loadNacosDataIfPresent(final CompositePropertySource composite, final String dataId, final String group, String fileExtension, boolean isRefreshable) {
		if (null == dataId || dataId.trim().length() < 1) {
			return; // dataId为空则直接跳过
		}
		if (null == group || group.trim().length() < 1) {
			return; // group为空则直接跳过，一般有默认值
		}
		NacosPropertySource propertySource = this.loadNacosPropertySource(dataId, group, fileExtension, isRefreshable);
		this.addFirstPropertySource(composite, propertySource, false); // 将加载的propertySource添加到composite中队列首部
	}

	private NacosPropertySource loadNacosPropertySource(final String dataId, final String group, String fileExtension, boolean isRefreshable) {
		if (NacosContextRefresher.getRefreshCount() != 0) { // 若已经被加载过了
			if (!isRefreshable) { // 对于扩展和共享配置，默认不支持动态刷新
				return NacosPropertySourceRepository.getNacosPropertySource(dataId, group);
			}
		}
		return nacosPropertySourceBuilder.build(dataId, group, fileExtension, isRefreshable); // 默认扩展名fileExtension为properties
	}

	/**
	 * Add the nacos configuration to the first place and maybe ignore the empty
	 * configuration.
	 */
	private void addFirstPropertySource(final CompositePropertySource composite, NacosPropertySource nacosPropertySource, boolean ignoreEmpty) {
		if (null == nacosPropertySource || null == composite) {
			return;
		}
		if (ignoreEmpty && nacosPropertySource.getSource().isEmpty()) {
			return;
		}
		composite.addFirstPropertySource(nacosPropertySource);
	}

	public void setNacosConfigManager(NacosConfigManager nacosConfigManager) {
		this.nacosConfigManager = nacosConfigManager;
	}

}
