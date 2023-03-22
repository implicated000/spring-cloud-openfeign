/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.openfeign;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AbstractClassTestingTypeFilter;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 * @author Gang Li
 */
class FeignClientsRegistrar
	implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar

	private ResourceLoader resourceLoader;

	private Environment environment;

	FeignClientsRegistrar() {
	}

	static void validateFallback(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(),
			"Fallback class must implement the interface annotated by @FeignClient");
	}

	static void validateFallbackFactory(final Class clazz) {
		Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
			+ "of fallback classes that implement the interface annotated by @FeignClient");
	}

	static String getName(String name) {
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			} else {
				url = name;
			}
			host = new URI(url).getHost();

		} catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	static String getUrl(String url) {
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			} catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	static String getPath(String path) {
		if (StringUtils.hasText(path)) {
			path = path.trim();
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata,
										BeanDefinitionRegistry registry) {
		// 给 @EnableFeignClients 的全局默认配置（注解的 defaultConfiguration 属性）创建 BeanDefinition 对象并注入到容器中
		registerDefaultConfiguration(metadata, registry);
		// 给标有了 @FeignClient 的类创建 BeanDefinition 对象并注入到容器中
		registerFeignClients(metadata, registry);
	}

	private void registerDefaultConfiguration(AnnotationMetadata metadata,
											  BeanDefinitionRegistry registry) {
		Map<String, Object> defaultAttrs = metadata
			.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			} else {
				name = "default." + metadata.getClassName();
			}
			registerClientConfiguration(registry, name, defaultAttrs.get("defaultConfiguration"));
		}
	}

	public void registerFeignClients(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// 获取ClassPath扫描器
		ClassPathScanningCandidateComponentProvider scanner = getScanner();
		// 为扫描器设置资源加载器
		scanner.setResourceLoader(this.resourceLoader);

		Set<String> basePackages;

		// 获取 @EnableFeignClients 注解的属性 map
		Map<String, Object> attrs = metadata
			.getAnnotationAttributes(EnableFeignClients.class.getName());
		AnnotationTypeFilter annotationTypeFilter = new AnnotationTypeFilter(
			FeignClient.class);
		// 获取 @EnableFeignClients 注解的 clients 属性
		final Class<?>[] clients = attrs == null ? null
			: (Class<?>[]) attrs.get("clients");
		// 如果 @EnableFeignClients 注解未指定 clients 属性则扫描添加（扫描过滤条件为：标注有 @FeignClient 的类）
		if (clients == null || clients.length == 0) {
			scanner.addIncludeFilter(annotationTypeFilter);
			basePackages = getBasePackages(metadata);
		}
		// 如果 @EnableFeignClients 注解已指定 clients 属性，则直接添加，不再扫描（从这里可以看出，为了加快容器启动速度，建议都指定 clients 属性）
		else {
			final Set<String> clientClasses = new HashSet<>();
			basePackages = new HashSet<>();
			// 扫描 client
			for (Class<?> clazz : clients) {
				basePackages.add(ClassUtils.getPackageName(clazz));
				clientClasses.add(clazz.getCanonicalName());
			}
			// 过滤标注有 @FeignClient 的类
			AbstractClassTestingTypeFilter filter = new AbstractClassTestingTypeFilter() {
				@Override
				protected boolean match(ClassMetadata metadata) {
					String cleaned = metadata.getClassName().replaceAll("\\$", ".");
					return clientClasses.contains(cleaned);
				}
			};
			// 同时满足
			scanner.addIncludeFilter(
				new AllTypeFilter(Arrays.asList(filter, annotationTypeFilter)));
		}

		// 遍历最终获取到的 @FeignClient 注解类的集合
		for (String basePackage : basePackages) {
			Set<BeanDefinition> candidateComponents = scanner
				.findCandidateComponents(basePackage);
			for (BeanDefinition candidateComponent : candidateComponents) {
				// 判断是否是带有注解的 Bean
				if (candidateComponent instanceof AnnotatedBeanDefinition) {
					// 验证带注释的类必须是接口，不是接口则直接抛出异常
					// verify annotated class is an interface
					AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
					AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
					Assert.isTrue(annotationMetadata.isInterface(),
						"@FeignClient can only be specified on an interface");

					// 获取 @FeignClient 注解的属性值
					Map<String, Object> attributes = annotationMetadata
						.getAnnotationAttributes(
							FeignClient.class.getCanonicalName());

					// 获取 clientName 的值，也就是在构造器的参数值
					String name = getClientName(attributes);
					// 同上一个方法最后调用的方法，注入 @FeignClient中的configuration对象到容器中，name：name.feignClientSpecification
					registerClientConfiguration(registry, name, attributes.get("configuration"));

					// 循环注入 @FeignClient 对象，name：className
					registerFeignClient(registry,
						annotationMetadata,
						attributes);
				}
			}
		}
	}

	private void registerFeignClient(BeanDefinitionRegistry registry, AnnotationMetadata annotationMetadata, Map<String, Object> attributes) {
		// 获取类名称
		String className = annotationMetadata.getClassName();
		// BeanDefinitionBuilder的主要作用就是构建一个AbstractBeanDefinition,AbstractBeanDefinition类最终被构建成一个BeanDefinitionHolder然后注册到Spring中
		// 注意：beanDefinition类为FeignClientFactoryBean，故在Spring获取类的时候实际返回的是FeignClientFactoryBean类
		BeanDefinitionBuilder definition = BeanDefinitionBuilder
			.genericBeanDefinition(FeignClientFactoryBean.class);
		validate(attributes);
		// 添加FeignClientFactoryBean的属性，
		definition.addPropertyValue("url", getUrl(attributes));
		definition.addPropertyValue("path", getPath(attributes));
		String name = getName(attributes);
		definition.addPropertyValue("name", name);
		// Feign 容器名称
		String contextId = getContextId(attributes);
		definition.addPropertyValue("contextId", contextId);
		definition.addPropertyValue("type", className);
		definition.addPropertyValue("decode404", attributes.get("decode404"));
		definition.addPropertyValue("fallback", attributes.get("fallback"));
		definition.addPropertyValue("fallbackFactory", attributes.get("fallbackFactory"));
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);

		// 设置别名
		String alias = contextId + "FeignClient";
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();

		boolean primary = (Boolean) attributes.get("primary"); // has a default, won't be
		// null

		beanDefinition.setPrimary(primary);

		String qualifier = getQualifier(attributes);
		if (StringUtils.hasText(qualifier)) {
			alias = qualifier;
		}

		// 定义BeanDefinitionHolder，name是 Feign标识的className
		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className,
			new String[]{alias});
		// 注入FeignClientFactoryBean
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
	}

	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		// FIXME annotation.getAliasedString("name", FeignClient.class, null);
		validateFallback(annotation.getClass("fallback"));
		validateFallbackFactory(annotation.getClass("fallbackFactory"));
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		name = resolve(name);
		return getName(name);
	}

	private String getContextId(Map<String, Object> attributes) {
		String contextId = (String) attributes.get("contextId");
		if (!StringUtils.hasText(contextId)) {
			return getName(attributes);
		}

		contextId = resolve(contextId);
		// check contextId
		return getName(contextId);
	}

	private String resolve(String value) {
		if (StringUtils.hasText(value)) {
			return this.environment.resolvePlaceholders(value);
		}
		return value;
	}

	private String getUrl(Map<String, Object> attributes) {
		String url = resolve((String) attributes.get("url"));
		return getUrl(url);
	}

	private String getPath(Map<String, Object> attributes) {
		String path = resolve((String) attributes.get("path"));
		return getPath(path);
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(
				AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}

	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		Map<String, Object> attributes = importingClassMetadata
			.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(
				ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}

	private String getClientName(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String value = (String) client.get("contextId");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("value");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException("Either 'name' or 'value' must be provided in @"
			+ FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name,
											 Object configuration) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder
			.genericBeanDefinition(FeignClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		// 注入 FeignClientSpecification （包含 ）EnableFeignClients.defaultConfiguration
		registry.registerBeanDefinition(
			name + "." + FeignClientSpecification.class.getSimpleName(),
			builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/**
	 * Helper class to create a {@link TypeFilter} that matches if all the delegates
	 * match.
	 *
	 * @author Oliver Gierke
	 */
	private static class AllTypeFilter implements TypeFilter {

		private final List<TypeFilter> delegates;

		/**
		 * Creates a new {@link AllTypeFilter} to match if all the given delegates match.
		 *
		 * @param delegates must not be {@literal null}.
		 */
		AllTypeFilter(List<TypeFilter> delegates) {
			Assert.notNull(delegates, "This argument is required, it must not be null");
			this.delegates = delegates;
		}

		@Override
		public boolean match(MetadataReader metadataReader,
							 MetadataReaderFactory metadataReaderFactory) throws IOException {

			for (TypeFilter filter : this.delegates) {
				if (!filter.match(metadataReader, metadataReaderFactory)) {
					return false;
				}
			}

			return true;
		}

	}

}
