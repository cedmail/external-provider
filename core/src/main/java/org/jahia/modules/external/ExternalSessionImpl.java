/**
 * ==========================================================================================
 * =                   JAHIA'S DUAL LICENSING - IMPORTANT INFORMATION                       =
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2015 Jahia Solutions Group SA. All rights reserved.
 *
 *     THIS FILE IS AVAILABLE UNDER TWO DIFFERENT LICENSES:
 *     1/GPL OR 2/JSEL
 *
 *     1/ GPL
 *     ======================================================================================
 *
 *     IF YOU DECIDE TO CHOSE THE GPL LICENSE, YOU MUST COMPLY WITH THE FOLLOWING TERMS:
 *
 *     "This program is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation; either version 2
 *     of the License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
 *     As a special exception to the terms and conditions of version 2.0 of
 *     the GPL (or any later version), you may redistribute this Program in connection
 *     with Free/Libre and Open Source Software ("FLOSS") applications as described
 *     in Jahia's FLOSS exception. You should have received a copy of the text
 *     describing the FLOSS exception, also available here:
 *     http://www.jahia.com/license"
 *
 *     2/ JSEL - Commercial and Supported Versions of the program
 *     ======================================================================================
 *
 *     IF YOU DECIDE TO CHOOSE THE JSEL LICENSE, YOU MUST COMPLY WITH THE FOLLOWING TERMS:
 *
 *     Alternatively, commercial and supported versions of the program - also known as
 *     Enterprise Distributions - must be used in accordance with the terms and conditions
 *     contained in a separate written agreement between you and Jahia Solutions Group SA.
 *
 *     If you are unsure which license is appropriate for your use,
 *     please contact the sales department at sales@jahia.com.
 *
 *
 * ==========================================================================================
 * =                                   ABOUT JAHIA                                          =
 * ==========================================================================================
 *
 *     Rooted in Open Source CMS, Jahia’s Digital Industrialization paradigm is about
 *     streamlining Enterprise digital projects across channels to truly control
 *     time-to-market and TCO, project after project.
 *     Putting an end to “the Tunnel effect”, the Jahia Studio enables IT and
 *     marketing teams to collaboratively and iteratively build cutting-edge
 *     online business solutions.
 *     These, in turn, are securely and easily deployed as modules and apps,
 *     reusable across any digital projects, thanks to the Jahia Private App Store Software.
 *     Each solution provided by Jahia stems from this overarching vision:
 *     Digital Factory, Workspace Factory, Portal Factory and eCommerce Factory.
 *     Founded in 2002 and headquartered in Geneva, Switzerland,
 *     Jahia Solutions Group has its North American headquarters in Washington DC,
 *     with offices in Chicago, Toronto and throughout Europe.
 *     Jahia counts hundreds of global brands and governmental organizations
 *     among its loyal customers, in more than 20 countries across the globe.
 *
 *     For more information, please visit http://www.jahia.com
 */
package org.jahia.modules.external;

import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.core.security.JahiaLoginModule;
import org.jahia.services.content.JCRStoreProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.jcr.*;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.retention.RetentionManager;
import javax.jcr.security.AccessControlManager;
import javax.jcr.version.VersionException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessControlException;
import java.util.*;

/**
 * Implementation of the {@link javax.jcr.Session} for the {@link org.jahia.modules.external.ExternalData}.
 *
 * @author Thomas Draier
 */
public class ExternalSessionImpl implements Session {
    static final String TRANSLATION_PREFIX = "translation:";
    static final String TRANSLATION_NODE_NAME_BASE = "j:translation_";
    private ExternalRepositoryImpl repository;
    private ExternalWorkspaceImpl workspace;
    private Credentials credentials;
    private Map<String, ExternalNodeImpl> nodesByPath = new LinkedHashMap<String, ExternalNodeImpl>();
    private Map<String, ExternalNodeImpl> nodesByIdentifier = new LinkedHashMap<String, ExternalNodeImpl>();
    private Set<ExternalItemImpl> newItems = new HashSet<ExternalItemImpl>();
    private Map<String, ExternalData> changedData = new LinkedHashMap<String, ExternalData>();
    private Map<String, ExternalData> deletedData = new LinkedHashMap<String, ExternalData>();
    private Map<String, List<String>> orderedData = new LinkedHashMap<String, List<String>>();
    private Set<Binary> tempBinaries = new HashSet<Binary>();
    private Session extensionSession;
    private List<String> extensionAllowedTypes;
    private List<String> extensionForbiddenMixins;
    private Map<String,List<String>> overridableProperties;
    private static final Logger logger = LoggerFactory.getLogger(ExternalSessionImpl.class);

    public ExternalSessionImpl(ExternalRepositoryImpl repository, Credentials credentials, String workspaceName) {
        this.repository = repository;
        this.workspace = new ExternalWorkspaceImpl(this, workspaceName);
        this.credentials = credentials;
    }

    public ExternalRepositoryImpl getRepository() {
        return repository;
    }

    public String getUserID() {
        return ((SimpleCredentials) credentials).getUserID();
    }

    public Object getAttribute(String s) {
        return null;
    }

    public String[] getAttributeNames() {
        return new String[0];
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Session impersonate(Credentials credentials) throws LoginException, RepositoryException {
        return this;
    }

    public Node getRootNode() throws RepositoryException {
        if (nodesByPath.containsKey("/")) {
            return nodesByPath.get("/");
        }
        ExternalData rootFileObject = repository.getDataSource().getItemByPath("/");
        final ExternalNodeImpl externalNode = new ExternalNodeImpl(rootFileObject, this);
        registerNode(externalNode);
        return externalNode;
    }

    public Node getNodeByUUID(String uuid) throws ItemNotFoundException, RepositoryException {
        if (nodesByIdentifier.containsKey(uuid)) {
            return nodesByIdentifier.get(uuid);
        }
        if (!repository.getDataSource().isSupportsUuid() || uuid.startsWith(TRANSLATION_PREFIX)) {
            if (!uuid.startsWith(getRepository().getStoreProvider().getId())) {
                throw new ItemNotFoundException("Item " + uuid + " could not be found in this repository");
            }
            // Translate UUID to external mapping
            String externalId = repository.getStoreProvider().getExternalProviderInitializerService().getExternalIdentifier(uuid);
            if (externalId == null) {
                throw new ItemNotFoundException("Item " + uuid + " could not be found in this repository");
            }
            uuid = externalId;
        }
        return getNodeByLocalIdentifier(uuid);
    }

    private Node getNodeByLocalIdentifier(String uuid) throws RepositoryException {
        for (ExternalItemImpl i : newItems) {
            if (i instanceof ExternalNodeImpl) {
                ExternalNodeImpl n = (ExternalNodeImpl) i;
                if (uuid.equals(n.getIdentifier())) {
                    return n;
                }
            }
        }

        for (ExternalData d : changedData.values()) {
            if (uuid.equals(d.getId())) {
                return new ExternalNodeImpl(d, this);
            }
        }

        if (uuid.startsWith(TRANSLATION_PREFIX)) {
            String u = StringUtils.substringAfter(uuid, TRANSLATION_PREFIX);
            String lang = StringUtils.substringBefore(u, ":");
            u = StringUtils.substringAfter(u, ":");
            return getNodeByLocalIdentifier(u).getNode(TRANSLATION_NODE_NAME_BASE + lang);
        }

        try {
            if (getExtensionSession() != null) {
                Node n = getExtensionSession().getNodeByIdentifier(uuid);
                return new ExtensionNode(n, StringUtils.substringAfter(n.getPath(), repository.getStoreProvider().getMountPoint()), this);
            }
        } catch (RepositoryException e) {
            // do nothing
        }
        Node n = new ExternalNodeImpl(repository.getDataSource().getItemByIdentifier(uuid), this);
        if (deletedData.containsKey(n.getPath())) {
            throw new ItemNotFoundException("This node has been deleted");
        }
        return n;
    }

    public Item getItem(String path) throws RepositoryException {

        path = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (deletedData.containsKey(path)) {
            throw new PathNotFoundException("This node has been deleted");
        }
        if (nodesByPath.containsKey(path)) {
            return nodesByPath.get(path);
        }
        String parentPath = StringUtils.substringBeforeLast(path, "/");
        if (parentPath.equals("")) {
            parentPath = "/";
        }
        try {
            if (StringUtils.substringAfterLast(parentPath, "/").startsWith(TRANSLATION_NODE_NAME_BASE)) {
                // Getting a translation property
                return getNode(parentPath).getProperty(StringUtils.substringAfterLast(path, "/"));
            } else if (StringUtils.substringAfterLast(path, "/").startsWith(TRANSLATION_NODE_NAME_BASE)) {
                // Getting translation node
                String lang = StringUtils.substringAfterLast(path, TRANSLATION_NODE_NAME_BASE);

                ExternalData parentObject;
                if (nodesByPath.containsKey(parentPath)) {
                    parentObject = nodesByPath.get(parentPath).getData();
                } else {
                    parentObject = repository.getDataSource().getItemByPath(parentPath);
                    final ExternalNodeImpl node = new ExternalNodeImpl(parentObject, this);
                    registerNode(node);
                }
                if ((parentObject.getI18nProperties() == null || !parentObject.getI18nProperties().containsKey(lang)) &&
                        (parentObject.getLazyI18nProperties() == null || !parentObject.getLazyI18nProperties().containsKey(lang))) {
                    throw new PathNotFoundException(path);
                }
                Map<String, String[]> i18nProps = new HashMap<String, String[]>();
                if (parentObject.getI18nProperties() != null && parentObject.getI18nProperties().containsKey(lang)) {
                    i18nProps.putAll(parentObject.getI18nProperties().get(lang));
                }
                i18nProps.put("jcr:language", new String[]{lang});
                ExternalData i18n = new ExternalData(TRANSLATION_PREFIX + lang + ":" + parentObject.getId(), path,
                        "jnt:translation", i18nProps);
                if (parentObject.getLazyI18nProperties() != null && parentObject.getLazyI18nProperties().containsKey(lang)) {
                    i18n.setLazyProperties(parentObject.getLazyI18nProperties().get(lang));
                }

                final ExternalNodeImpl node = new ExternalNodeImpl(i18n, this);
                registerNode(node);
                return node;
            } else {
                String itemName = StringUtils.substringAfterLast(path, "/");
                if (getRepository().getStoreProvider().getReservedNodes().contains(itemName)) {
                    throw new PathNotFoundException(path);
                }
                // Try to get the item as a node
                try {
                    ExternalData data = repository.getDataSource().getItemByPath(path);
                    final ExternalNodeImpl node = new ExternalNodeImpl(data, this);
                    registerNode(node);
                    return node;
                } catch (PathNotFoundException e) {
                    // Or a property in the parent node
                    if (!nodesByPath.containsKey(parentPath)) {
                        ExternalData data = repository.getDataSource().getItemByPath(parentPath);
                        final ExternalNodeImpl node = new ExternalNodeImpl(data, this);
                        registerNode(node);
                    }
                    return nodesByPath.get(parentPath).getProperty(itemName);
                }
            }
        } catch (PathNotFoundException e) {
            // In case item is not found in provider, lookup in extension provider if available
            if (getExtensionSession() != null && !StringUtils.equals("/", path)) {
                Item item = getExtensionSession().getItem(repository.getStoreProvider().getMountPoint() + path);
                return item.isNode() ?
                        new ExtensionNode((Node) item, path, this) :
                        new ExtensionProperty((Property) item, path, this, new ExtensionNode(item.getParent(), parentPath, this));
            } else {
                throw e;
            }
        }

    }

    protected String[] getPropertyValues(ExternalData data, String propertyName) throws PathNotFoundException {
        ExternalDataSource dataSource = repository.getDataSource();
        if (dataSource instanceof ExternalDataSource.LazyProperty) {
            return ((ExternalDataSource.LazyProperty) dataSource).getPropertyValues(data.getPath(), propertyName);
        } else {
            throw new PathNotFoundException(repository.getProviderKey() + " doesn't support lazy properties");
        }
    }

    protected String[] getI18nPropertyValues(ExternalData data, String lang, String propertyName) throws PathNotFoundException {
        ExternalDataSource dataSource = repository.getDataSource();
        if (dataSource instanceof ExternalDataSource.LazyProperty) {
            return ((ExternalDataSource.LazyProperty) dataSource).getI18nPropertyValues(StringUtils.substringBeforeLast(data.getPath(),"/"), lang, propertyName);
        } else {
            throw new PathNotFoundException(repository.getProviderKey() + " doesn't support lazy properties");
        }
    }

    protected Binary[] getBinaryPropertyValues(ExternalData data, String propertyName) throws PathNotFoundException {
        ExternalDataSource dataSource = repository.getDataSource();
        if (dataSource instanceof ExternalDataSource.LazyProperty) {
            return ((ExternalDataSource.LazyProperty) dataSource).getBinaryPropertyValues(data.getPath(), propertyName);
        } else {
            throw new PathNotFoundException(repository.getProviderKey() + " doesn't support lazy properties");
        }
    }

    @Override
    public boolean itemExists(String path) throws RepositoryException {
        try {
            getItem(path);
        } catch (PathNotFoundException e) {
            return false;
        }
        return true;
    }

    public void move(String source, String dest)
            throws ItemExistsException, PathNotFoundException, VersionException, ConstraintViolationException, LockException, RepositoryException {
        Item sourceNode = getItem(source);
        if (!sourceNode.isNode()) {
            throw new PathNotFoundException(source);
        }
        if (sourceNode instanceof ExtensionNode) {
            String targetName = StringUtils.substringAfterLast(dest, "/");
            String parentPath = StringUtils.substringBeforeLast(dest,"/");
            Node targetNode = (Node) getItem(parentPath);
            final String srcAbsPath = ((ExtensionNode) sourceNode).getJcrNode().getPath();
            Node jcrNode = null;
            if (targetNode instanceof ExtensionNode) {
                jcrNode = ((ExtensionNode) targetNode).getJcrNode();
            } else if (targetNode instanceof ExternalNodeImpl) {
                final ExternalNodeImpl externalNode = (ExternalNodeImpl) targetNode;
                Node extendedNode = externalNode.getExtensionNode(true);
                if (extendedNode != null && externalNode.canItemBeExtended(targetName, ((ExtensionNode) sourceNode).getPrimaryNodeType().getName())) {
                    jcrNode = extendedNode;
                }
            }
            if (jcrNode != null) {
                getExtensionSession().move(srcAbsPath, jcrNode.getPath() + "/" + targetName);
                return;
            }
        } else if (sourceNode instanceof ExternalNodeImpl) {
            if (!(repository.getDataSource() instanceof ExternalDataSource.Writable)) {
                throw new UnsupportedRepositoryOperationException();
            }

            if (source.equals(dest)) {
                return;
            }

            final ExternalNodeImpl externalNode = (ExternalNodeImpl) sourceNode;


            final ExternalNodeImpl previousParent = (ExternalNodeImpl) externalNode.getParent();
            final List<String> previousParentChildren = previousParent.getExternalChildren();

            //todo : store move in session and move node in save
            ((ExternalDataSource.Writable) repository.getDataSource()).move(source, dest);

            int oldIndex =  previousParentChildren.indexOf(externalNode.getName());
            previousParentChildren.remove(externalNode.getName());
            unregisterNode(externalNode);

            ExternalData newData = repository.getDataSource().getItemByPath(dest);

            final ExternalNodeImpl newExternalNode = new ExternalNodeImpl(newData, this);
            registerNode(newExternalNode);

            final ExternalNodeImpl newParent = (ExternalNodeImpl) newExternalNode.getParent();
            if (newParent.equals(previousParent)) {
                previousParentChildren.add(oldIndex, newExternalNode.getName());
            } else if (!newParent.getExternalChildren().contains(newExternalNode.getName())) {
                newParent.getExternalChildren().add(newExternalNode.getName());
            }

            final ExternalData oldData = externalNode.getData();
            if (oldData.getId().equals(newData.getId())) {
                return;
            }
            getRepository()
                    .getStoreProvider()
                    .getExternalProviderInitializerService()
                    .updateExternalIdentifier(oldData.getId(), newData.getId(), getRepository().getProviderKey(),
                            getRepository().getDataSource().isSupportsHierarchicalIdentifiers());
            return;
        }

        throw new UnsupportedRepositoryOperationException();
     }

    public void save()
            throws AccessDeniedException, ItemExistsException, ConstraintViolationException, InvalidItemStateException, VersionException, LockException, NoSuchNodeTypeException, RepositoryException {
        if (extensionSession != null && extensionSession.hasPendingChanges()) {
            extensionSession.save();
        }
        if (!(repository.getDataSource() instanceof ExternalDataSource.Writable)) {
            deletedData.clear();
            changedData.clear();
            orderedData.clear();
            return;
        }
        Map<String, ExternalData> changedDataWithI18n = new LinkedHashMap<String, ExternalData>();
        for (Map.Entry<String, ExternalData> entry : changedData.entrySet()) {
            String path = entry.getKey();
            ExternalData externalData = entry.getValue();
            if (path.startsWith(TRANSLATION_NODE_NAME_BASE,path.lastIndexOf("/") + 1)) {
                String lang = StringUtils.substringAfterLast(path, TRANSLATION_NODE_NAME_BASE);
                String parentPath = StringUtils.substringBeforeLast(path, "/");
                ExternalData parentData;
                if (changedDataWithI18n.containsKey(parentPath)) {
                    parentData = changedDataWithI18n.get(parentPath);
                } else {
                    parentData = repository.getDataSource().getItemByPath(parentPath);
                }
                Map<String, Map<String, String[]>> i18nProperties = parentData.getI18nProperties();
                if (i18nProperties == null) {
                    i18nProperties = new HashMap<String, Map<String, String[]>>();
                    parentData.setI18nProperties(i18nProperties);
                }
                i18nProperties.put(lang, externalData.getProperties());

                if (externalData.getLazyProperties() != null) {
                    Map<String, Set<String>> lazyI18nProperties = parentData.getLazyI18nProperties();
                    if (lazyI18nProperties == null) {
                        lazyI18nProperties = new HashMap<String, Set<String>>();
                        parentData.setLazyI18nProperties(lazyI18nProperties);
                    }
                    lazyI18nProperties.put(lang, externalData.getLazyProperties());
                }

                changedDataWithI18n.put(parentPath, parentData);
            } else {
                changedDataWithI18n.put(path, externalData);
            }
        }
        ExternalDataSource.Writable writableDataSource = (ExternalDataSource.Writable) repository.getDataSource();
        for (String path : orderedData.keySet()) {
            writableDataSource.order(path, orderedData.get(path));
        }
        orderedData.clear();
        for (ExternalData data : changedDataWithI18n.values()) {
            writableDataSource.saveItem(data);
        }

        changedData.clear();
        if (!deletedData.isEmpty()) {
            List<String> toBeDeleted = new LinkedList<String>();
            for (String path : deletedData.keySet()) {
                writableDataSource.removeItemByPath(path);
                toBeDeleted.add(deletedData.get(path).getId());
            }
            getRepository()
                    .getStoreProvider()
                    .getExternalProviderInitializerService()
                    .delete(toBeDeleted, getRepository().getStoreProvider().getKey(),
                            getRepository().getDataSource().isSupportsHierarchicalIdentifiers());
            deletedData.clear();
        }
        for (ExternalItemImpl newItem : newItems) {
            newItem.setNew(false);
        }
        newItems.clear();
    }

    public void refresh(boolean keepChanges) throws RepositoryException {
        if (!keepChanges) {
            deletedData.clear();
            changedData.clear();
            orderedData.clear();
            newItems.clear();
            nodesByPath.clear();
            nodesByIdentifier.clear();
        } else {
            List<String> pathsToKeep = new ArrayList<String>();
            pathsToKeep.addAll(changedData.keySet());
            pathsToKeep.addAll(deletedData.keySet());
            pathsToKeep.addAll(orderedData.keySet());
            List<String> idsToKeep = new ArrayList<String>();
            for (String s : pathsToKeep) {
                if (nodesByPath.containsKey(s)) {
                    idsToKeep.add(nodesByPath.get(s).getIdentifier());
                }
            }
            nodesByPath.keySet().retainAll(pathsToKeep);
            nodesByIdentifier.keySet().retainAll(idsToKeep);
        }
    }

    public boolean hasPendingChanges() throws RepositoryException {
        return false;
    }

    public ExternalValueFactoryImpl getValueFactory()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        return new ExternalValueFactoryImpl(this);
    }

    public void checkPermission(String s, String s1) throws AccessControlException, RepositoryException {

    }

    public ContentHandler getImportContentHandler(String s, int i)
            throws PathNotFoundException, ConstraintViolationException, VersionException, LockException, RepositoryException {
        return null;
    }

    public void importXML(String s, InputStream inputStream, int i)
            throws IOException, PathNotFoundException, ItemExistsException, ConstraintViolationException, VersionException, InvalidSerializedDataException, LockException, RepositoryException {

    }

    public void exportSystemView(String s, ContentHandler contentHandler, boolean b, boolean b1)
            throws PathNotFoundException, SAXException, RepositoryException {

    }

    public void exportSystemView(String s, OutputStream outputStream, boolean b, boolean b1)
            throws IOException, PathNotFoundException, RepositoryException {

    }

    public void exportDocumentView(String s, ContentHandler contentHandler, boolean b, boolean b1)
            throws PathNotFoundException, SAXException, RepositoryException {

    }

    public void exportDocumentView(String s, OutputStream outputStream, boolean b, boolean b1)
            throws IOException, PathNotFoundException, RepositoryException {

    }

    public void setNamespacePrefix(String s, String s1) throws NamespaceException, RepositoryException {

    }

    public String[] getNamespacePrefixes() throws RepositoryException {
        return workspace.getNamespaceRegistry().getPrefixes();
    }

    public String getNamespaceURI(String s) throws NamespaceException, RepositoryException {
        return workspace.getNamespaceRegistry().getURI(s);
    }

    public String getNamespacePrefix(String s) throws NamespaceException, RepositoryException {
        return workspace.getNamespaceRegistry().getPrefix(s);
    }

    public void logout() {
        if (extensionSession != null && extensionSession.isLive()) {
            extensionSession.logout();
            extensionSession = null;
        }
        for (Binary binary : tempBinaries) {
            binary.dispose();
        }
    }

    public boolean isLive() {
        return true;
    }

    public void addLockToken(String s) {
        try {
            LockManager extensionLockManager = getExtensionSession().getWorkspace().getLockManager();
            if (extensionLockManager != null) {
                extensionLockManager.addLockToken(s);
            }
        } catch (RepositoryException e) {
            logger.error("Unable to add lock token "+ s,e.getMessage());
        }
    }

    public String[] getLockTokens() {
        try {
            if (getExtensionSession() == null) {
                return new String[0];
            }
            return getExtensionSession().getWorkspace().getLockManager().getLockTokens();
        } catch (RepositoryException e) {
            return new String[0];
        }
    }

    public void removeLockToken(String s) {
        try {
            LockManager extensionLockManager = getExtensionSession().getWorkspace().getLockManager();
            if (extensionLockManager != null) {
                extensionLockManager.removeLockToken(s);
            }
        } catch (RepositoryException e) {
            logger.error("Unable to remove lock token " + s,e.getMessage());
        }

    }

    public Map<String, ExternalData> getChangedData() {
        return changedData;
    }

    public Map<String, ExternalData> getDeletedData() {
        return deletedData;
    }

    public Map<String, List<String>> getOrderedData() {
        return orderedData;
    }

    public void registerNode(ExternalNodeImpl node) throws RepositoryException {
        nodesByPath.put(node.getPath(), node);
        nodesByIdentifier.put(node.getIdentifier(), node);
    }

    public void unregisterNode(ExternalNodeImpl node) throws RepositoryException {
        nodesByPath.remove(node.getPath());
        nodesByIdentifier.remove(node.getIdentifier());
        changedData.remove(node.getPath());
        orderedData.remove(node.getPath());
        newItems.remove(node);
    }

    public void registerTemporaryBinary(Binary binary) throws RepositoryException {
        tempBinaries.add(binary);
    }


    public Set<ExternalItemImpl> getNewItems() {
        return newItems;
    }

    public void setNewItem(ExternalItemImpl newItem) throws RepositoryException {
        newItem.setNew(true);
        newItems.add(newItem);
    }

    public Node getNodeByIdentifier(String id) throws ItemNotFoundException, RepositoryException {
        return getNodeByUUID(id);
    }

    public Node getNode(String absPath) throws PathNotFoundException, RepositoryException {
        return (Node) getItem(absPath);
    }

    public Property getProperty(String absPath) throws PathNotFoundException, RepositoryException {
        return (Property) getItem(absPath);
    }

    public boolean nodeExists(String absPath) throws RepositoryException {
        return itemExists(absPath);
    }

    public boolean propertyExists(String absPath) throws RepositoryException {
        return itemExists(absPath);
    }

    public void removeItem(String absPath)
            throws VersionException, LockException, ConstraintViolationException, AccessDeniedException, RepositoryException {
        getItem(absPath).remove();
    }

    public boolean hasPermission(String absPath, String actions) throws RepositoryException {
        // TODO implement me
        return false;
    }

    public boolean hasCapability(String s, Object o, Object[] objects) throws RepositoryException {
        // TODO implement me
        return false;
    }

    public AccessControlManager getAccessControlManager()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        return repository.getAccessControlManager();
    }

    public RetentionManager getRetentionManager() throws UnsupportedRepositoryOperationException, RepositoryException {
        return null;
    }

    public Session getExtensionSession() throws RepositoryException {
        if (extensionSession == null) {
            JCRStoreProvider extensionProvider = getRepository().getStoreProvider().getExtensionProvider();
            if (extensionProvider != null) {
                extensionSession = extensionProvider.getSession(JahiaLoginModule.getSystemCredentials(StringUtils.removeStart(getUserID(), JahiaLoginModule.SYSTEM)), "default");
            }
        }
        return extensionSession;
    }

    public List<String> getExtensionAllowedTypes() throws RepositoryException {
        if (extensionAllowedTypes == null) {
            extensionAllowedTypes = getRepository().getStoreProvider().getExtendableTypes();
            if (extensionAllowedTypes == null) {
                extensionAllowedTypes = Arrays.asList("nt:base");
            }
        }
        return extensionAllowedTypes;
    }

    /**
     * Return the properties that can be overridden for extendable nodetypes
     * @return a map of list of properties (value) by nodetype (key)
     */
    public Map<String,List<String>> getOverridableProperties() {
        if (overridableProperties == null) {
            overridableProperties = new HashMap<String,List<String>>();
            List<String> overridablePropertiesString = getRepository().getStoreProvider().getOverridableItems();
            if (overridablePropertiesString != null) {
                for (String s : overridablePropertiesString) {
                    String nodeType = StringUtils.substringBefore(s,".");
                    String property = StringUtils.substringAfter(s,".");
                    if (!overridableProperties.containsKey(nodeType)) {
                        overridableProperties.put(nodeType, new ArrayList<String>());
                    }
                    overridableProperties.get(nodeType).add(property);
                }
            }
        }
        return overridableProperties;
    }

    public List<String> getExtensionForbiddenMixins() throws RepositoryException {
        if (extensionForbiddenMixins == null) {
            extensionForbiddenMixins = getRepository().getStoreProvider().getNonExtendableMixins();
            if (extensionForbiddenMixins == null) {
                extensionForbiddenMixins = Collections.emptyList();
            }
        }
        return extensionForbiddenMixins;
    }
}
