package com.github.mkouba.jasolo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Minimal read-only view of a {@code pom.xml} - just enough to map
 * {@code groupId:artifactId} to a module directory and to walk the module tree.
 * <p>
 * Inheritance follows Maven semantics: a missing {@code <groupId>} or
 * {@code <version>} is inherited from the {@code <parent>} element.
 */
final class PomInfo {

    final String groupId;
    final String artifactId;
    final String version;
    final String packaging;
    final List<String> modules;

    private PomInfo(String groupId, String artifactId, String version, String packaging, List<String> modules) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.packaging = packaging;
        this.modules = modules;
    }

    static PomInfo parse(Path pomPath) {
        try (InputStream in = Files.newInputStream(pomPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(in);
            Element project = document.getDocumentElement();

            String ownGroupId = firstChildText(project, "groupId");
            String ownVersion = firstChildText(project, "version");
            String parentGroupId = null;
            String parentVersion = null;
            Element parent = firstChildElement(project, "parent");
            if (parent != null) {
                parentGroupId = firstChildText(parent, "groupId");
                parentVersion = firstChildText(parent, "version");
            }
            String artifactId = firstChildText(project, "artifactId");
            if (artifactId == null || artifactId.isBlank()) {
                throw new IllegalStateException("pom.xml without artifactId: " + pomPath);
            }
            String packaging = firstChildText(project, "packaging");
            List<String> modules = new ArrayList<>();
            Element modulesEl = firstChildElement(project, "modules");
            if (modulesEl != null) {
                NodeList moduleNodes = modulesEl.getElementsByTagName("module");
                for (int i = 0; i < moduleNodes.getLength(); i++) {
                    String module = moduleNodes.item(i).getTextContent().trim();
                    if (!module.isEmpty()) {
                        modules.add(module);
                    }
                }
            }
            return new PomInfo(
                    ownGroupId != null ? ownGroupId : parentGroupId,
                    artifactId,
                    ownVersion != null ? ownVersion : parentVersion,
                    packaging != null ? packaging : "jar",
                    modules);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse " + pomPath, e);
        }
    }

    private static Element firstChildElement(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static String firstChildText(Element parent, String name) {
        Element element = firstChildElement(parent, name);
        if (element == null) {
            return null;
        }
        String text = element.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }
}
