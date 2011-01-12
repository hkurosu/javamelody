/*
 * Copyright 2008-2010 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Java Melody is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Java Melody is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Java Melody.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.bull.javamelody;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Objet récupérant une instance de MBeanServer lors de sa construction
 * et permettant de récupérer différentes données sur les MBeans.
 * @author Emeric Vernat
 */
final class MBeans {
	private final MBeanServer mbeanServer;

	MBeans() {
		super();
		mbeanServer = getMBeanServer();
	}

	Set<ObjectName> getThreadPools() throws MalformedObjectNameException {
		return mbeanServer.queryNames(new ObjectName("*:type=ThreadPool,*"), null);
	}

	Set<ObjectName> getGlobalRequestProcessors() throws MalformedObjectNameException {
		return mbeanServer.queryNames(new ObjectName("*:type=GlobalRequestProcessor,*"), null);
	}

	Object getAttribute(ObjectName name, String attribute) throws JMException {
		return mbeanServer.getAttribute(name, attribute);
	}

	private static void initJRockitMBeansIfNeeded() {
		// si jrockit, on initialise les MBeans spécifiques jrockit lors de la première demande
		if (System.getProperty("java.vendor").contains("BEA")) {
			try {
				// initialisation des MBeans jrockit comme indiqué dans http://blogs.oracle.com/hirt/jrockit/
				try {
					getMBeanServer().getMBeanInfo(
							new ObjectName("bea.jrockit.management:type=JRockitConsole"));
				} catch (final InstanceNotFoundException e1) {
					getMBeanServer().createMBean("bea.jrockit.management.JRockitConsole", null);
					LOG.debug("JRockit MBeans initialized");
				}
			} catch (final JMException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	Map<String, Map<String, List<ObjectName>>> getMapObjectNamesByDomainAndFirstProperty() {
		initJRockitMBeansIfNeeded();

		// TreeMap et non HashMap ou LinkedHashMap pour trier par ordre des clés
		final Map<String, Map<String, List<ObjectName>>> mapObjectNamesByDomainAndFirstProperty = new TreeMap<String, Map<String, List<ObjectName>>>();
		final Set<ObjectName> names = mbeanServer.queryNames(null, null);
		for (final ObjectName name : names) {
			Map<String, List<ObjectName>> mapObjectNamesByFirstProperty = mapObjectNamesByDomainAndFirstProperty
					.get(name.getDomain());
			if (mapObjectNamesByFirstProperty == null) {
				mapObjectNamesByFirstProperty = new TreeMap<String, List<ObjectName>>();
				mapObjectNamesByDomainAndFirstProperty.put(name.getDomain(),
						mapObjectNamesByFirstProperty);
			}
			final String keyPropertyListString = name.getKeyPropertyListString();
			final String firstPropertyValue;
			final int indexOf = keyPropertyListString.indexOf('=');
			if (indexOf == -1) {
				// n'arrive probablement pas, mais au cas où
				firstPropertyValue = null;
			} else {
				firstPropertyValue = name.getKeyProperty(keyPropertyListString
						.substring(0, indexOf));
			}
			List<ObjectName> objectNames = mapObjectNamesByFirstProperty.get(firstPropertyValue);
			if (objectNames == null) {
				objectNames = new ArrayList<ObjectName>();
				mapObjectNamesByFirstProperty.put(firstPropertyValue, objectNames);
			}
			objectNames.add(name);
		}
		return mapObjectNamesByDomainAndFirstProperty;
	}

	MBeanInfo getMBeanInfo(ObjectName name) throws JMException {
		return mbeanServer.getMBeanInfo(name);
	}

	Map<String, Object> getAttributes(ObjectName name, MBeanAttributeInfo[] attributeInfos)
			throws JMException {
		final List<String> attributeNames = new ArrayList<String>(attributeInfos.length);
		for (final MBeanAttributeInfo attribute : attributeInfos) {
			// on ne veut pas afficher l'attribut password, jamais
			// (notamment, dans users tomcat ou dans datasources tomcat)
			if (attribute.isReadable() && !"password".equalsIgnoreCase(attribute.getName())) {
				attributeNames.add(attribute.getName());
			}
		}
		final String[] attributeNamesArray = attributeNames.toArray(new String[attributeNames
				.size()]);
		final List<Attribute> attributes = mbeanServer.getAttributes(name, attributeNamesArray)
				.asList();
		final Map<String, Object> result = new TreeMap<String, Object>();
		for (final Attribute attribute : attributes) {
			final Object value = convertValueIfNeeded(attribute.getValue());
			result.put(attribute.getName(), value);
		}
		return result;
	}

	private Object convertValueIfNeeded(Object value) {
		if (value instanceof CompositeData) {
			final CompositeData data = (CompositeData) value;
			final Map<String, Object> values = new TreeMap<String, Object>();
			for (final String key : data.getCompositeType().keySet()) {
				values.put(key, convertValueIfNeeded(data.get(key)));
			}
			return values;
		} else if (value instanceof CompositeData[]) {
			final List<Object> list = new ArrayList<Object>();
			for (final CompositeData data : (CompositeData[]) value) {
				list.add(convertValueIfNeeded(data));
			}
			return list;
		} else if (value instanceof Object[]) {
			return Arrays.asList((Object[]) value);
		} else if (value instanceof TabularData) {
			final TabularData tabularData = (TabularData) value;
			return convertValueIfNeeded(tabularData.values());
		} else if (value instanceof Collection) {
			final List<Object> list = new ArrayList<Object>();
			for (final Object data : (Collection<?>) value) {
				list.add(convertValueIfNeeded(data));
			}
			return list;
		}
		return convertJRockitValueIfNeeded(value);
	}

	private static Object convertJRockitValueIfNeeded(Object value) {
		if (value instanceof double[]) {
			// pour jrockit MBeans
			final List<Double> list = new ArrayList<Double>();
			for (final double data : (double[]) value) {
				list.add(data);
			}
			return list;
		} else if (value instanceof int[]) {
			// pour jrockit MBeans
			final List<Integer> list = new ArrayList<Integer>();
			for (final int data : (int[]) value) {
				list.add(data);
			}
			return list;
		}
		return value;
	}

	static String getConvertedAttributes(String jmxValueParameter) {
		initJRockitMBeansIfNeeded();

		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (final String mbeansAttribute : jmxValueParameter.split("[|]")) {
			final int lastIndexOfPoint = mbeansAttribute.lastIndexOf('.');
			if (lastIndexOfPoint <= 0) {
				throw new IllegalArgumentException(mbeansAttribute);
			}
			final String name = mbeansAttribute.substring(0, lastIndexOfPoint);
			final String attribute = mbeansAttribute.substring(lastIndexOfPoint + 1);
			// on ne veut pas afficher l'attribut password, jamais
			// (notamment, dans users tomcat ou dans datasources tomcat)
			if ("password".equalsIgnoreCase(attribute)) {
				throw new IllegalArgumentException(name + '.' + attribute);
			}
			if (first) {
				first = false;
			} else {
				sb.append('|');
			}
			try {
				final MBeans mbeans = new MBeans();
				final Object jmxValue = mbeans.convertValueIfNeeded(mbeans.getAttribute(
						new ObjectName(name), attribute));
				sb.append(jmxValue);
			} catch (final JMException e) {
				throw new IllegalArgumentException(name + '.' + attribute, e);
			}
		}
		return sb.toString();
	}

	String getAttributeDescription(String name, MBeanAttributeInfo[] attributeInfos) {
		for (final MBeanAttributeInfo attributeInfo : attributeInfos) {
			if (name.equals(attributeInfo.getName())) {
				return attributeInfo.getDescription();
			}
		}
		return null;
	}

	/**
	 * Retourne le javax.management.MBeanServer en le créant si nécessaire.
	 * @return MBeanServer
	 */
	private static MBeanServer getMBeanServer() {
		return ManagementFactory.getPlatformMBeanServer();
		// alternative (sauf pour hudson slaves):
		//		final List<MBeanServer> mBeanServers = MBeanServerFactory.findMBeanServer(null);
		//		if (!mBeanServers.isEmpty()) {
		//			// il existe déjà un MBeanServer créé précédemment par Tomcat ou bien ci-dessous
		//			return mBeanServers.get(0);
		//		}
		//		final MBeanServer server = MBeanServerFactory.createMBeanServer();
		//		return server;
	}
}
