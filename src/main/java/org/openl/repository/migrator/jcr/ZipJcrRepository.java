package org.openl.repository.migrator.jcr;

import org.openl.repository.migrator.jcr.api.ArtefactAPI;
import org.openl.repository.migrator.jcr.api.ArtefactProperties;
import org.openl.repository.migrator.jcr.api.FolderAPI;
import org.openl.repository.migrator.jcr.api.Property;
import org.openl.repository.migrator.jcr.api.ResourceAPI;
import org.openl.repository.migrator.jcr.exception.PropertyException;
import org.openl.repository.migrator.jcr.utils.NodeUtil;
import org.openl.rules.common.CommonException;
import org.openl.rules.common.ProjectException;
import org.openl.rules.common.ProjectVersion;
import org.openl.rules.common.impl.ArtefactPathImpl;
import org.openl.rules.common.impl.CommonVersionImpl;
import org.openl.rules.repository.api.Features;
import org.openl.rules.repository.api.FeaturesBuilder;
import org.openl.rules.repository.api.FileData;
import org.openl.rules.repository.api.FileItem;
import org.openl.rules.repository.api.Listener;
import org.openl.rules.repository.api.Repository;
import org.openl.rules.repository.exceptions.RRepositoryException;
import org.openl.util.IOUtils;
import org.openl.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.ObservationManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipJcrRepository implements Repository, Closeable, EventListener {
    private final Logger log = LoggerFactory.getLogger(ZipJcrRepository.class);

    private Session session;
    private Listener listener;
    // In this case there is no need to store a strong reference to the listener: current field is used only to remove
    // old instance. If it's GC-ed, no need to remove it.

    protected void init(Session session) throws RepositoryException {
        this.session = session;
        int eventTypes = Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED | Event.PROPERTY_REMOVED | Event.NODE_REMOVED;
        String[] nodeTypeName = {JcrNT.NT_COMMON_ENTITY};
        session.getWorkspace()
                .getObservationManager()
                .addEventListener(this, eventTypes, "/", true, null, nodeTypeName, false);
    }

    @Override
    public List<FileData> list(String path) throws IOException {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            Node node = checkFolder(session.getRootNode(), path, false);
            List<FileData> result = new ArrayList<>();
            if (node == null) {
                return result;
            }
            List<FolderAPI> projects = getChildren(node);

            if (path.equals("deploy")) {
                for (FolderAPI deployment : projects) {
                    for (ArtefactAPI artefactAPI : deployment.getArtefacts()) {
                        if (artefactAPI instanceof FolderAPI) {
                            result.add(createFileData(path + "/" + deployment.getName() + "/" + artefactAPI.getName(),
                                    artefactAPI));
                        }
                    }
                }
                return result;
            } else {
                for (FolderAPI project : projects) {
                    result.add(createFileData(path + "/" + project.getName(), project));
                }
            }

            return result;
        } catch (CommonException | RepositoryException e) {
            throw new IOException(e);
        }
    }

    private List<FolderAPI> getChildren(Node root) throws RRepositoryException {
        NodeIterator ni;
        try {
            ni = root.getNodes();
        } catch (RepositoryException e) {
            throw new RRepositoryException("Cannot get children nodes", e);
        }

        LinkedList<FolderAPI> result = new LinkedList<>();
        while (ni.hasNext()) {
            Node n = ni.nextNode();
            try {
                if (!n.isNodeType(JcrNT.NT_LOCK)) {
                    result.add(new JcrFolderAPI(n, new ArtefactPathImpl(new String[]{n.getName()})));
                }
            } catch (RepositoryException e) {
                log.debug("Failed to get a child node.", e);
            }
        }

        return result;
    }

    @Override
    public FileData check(String name) throws IOException {
        try {
            FolderAPI project = getOrCreateProject(name, false);
            if (project == null) {
                return null;
            }
            return createFileData(name, project);
        } catch (CommonException e) {
            throw new IOException(e);
        }
    }

    @Override
    public FileItem read(String name) throws IOException {
        try {
            FolderAPI project = getOrCreateProject(name, false);
            if (project == null) {
                return null;
            }
            return createFileItem(project, createFileData(name, project));
        } catch (CommonException e) {
            throw new IOException(e);
        }
    }

    @Override
    public FileData save(FileData data, InputStream stream) throws IOException {
        throw new UnsupportedOperationException();
    }

    private void addFolderPaths(TreeSet<String> folderPaths, String path) {
        int slashIndex = path.lastIndexOf('/');
        if (slashIndex > -1) {
            String folderPath = path.substring(0, slashIndex);
            folderPaths.add(folderPath);
            addFolderPaths(folderPaths, folderPath);
        }
    }

    @Override
    public List<FileData> save(List<FileItem> fileItems) throws IOException {
        List<FileData> result = new ArrayList<>();
        for (FileItem fileItem : fileItems) {
            result.add(save(fileItem.getData(), fileItem.getStream()));
        }
        return result;
    }

    @Override
    public boolean delete(FileData data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setListener(final Listener callback) {
        this.listener = callback;
    }

    @Override
    public void onEvent(EventIterator events) {
        while (listener != null && events.hasNext()) {
            try {
                listener.onChange();
                break;
            } catch (Exception e) {
                log.error("onEvent", e);
            }
        }
    }

    @Override
    public List<FileData> listHistory(String name) throws IOException {
        try {
            ArtefactAPI artefact = getArtefact(name);
            if (artefact == null || artefact instanceof ResourceAPI) {
                return Collections.emptyList();
            }

            FolderAPI project = (FolderAPI) artefact;
            List<FileData> result = new ArrayList<>();
            if (project.getVersionsCount() > 0) {
                for (ProjectVersion version : project.getVersions()) {
                    FolderAPI history = project.getVersion(version);
                    FileData fileData = createFileData(name, history);
                    //no need to add technical revision (Only in JCR)
                    if (fileData.getSize() == 0 && StringUtils.isBlank(fileData.getComment())) {
                        continue;
                    }
                    result.add(fileData);
                }
            }
            return result;
        } catch (CommonException e) {
            throw new IOException(e);
        }
    }

    @Override
    public FileData checkHistory(String name, String version) throws IOException {
        if (version == null) {
            return check(name);
        }
        try {
            ArtefactAPI artefact = getArtefact(name);
            if (artefact == null || artefact instanceof ResourceAPI) {
                return null;
            }

            FolderAPI project = (FolderAPI) artefact;

            FolderAPI history = project.getVersion(new CommonVersionImpl(Integer.parseInt(version)));
            return createFileData(name, history);
        } catch (CommonException e) {
            throw new IOException(e);
        } catch (NumberFormatException e) {
            throw new IOException("Project version must be a number.");
        }
    }

    @Override
    public FileItem readHistory(String name, String version) throws IOException {
        if (version == null) {
            return read(name);
        }
        try {
            ArtefactAPI artefact = getArtefact(name);
            if (artefact == null || artefact instanceof ResourceAPI) {
                return null;
            }

            FolderAPI project = (FolderAPI) artefact;

            FolderAPI history = project.getVersion(new CommonVersionImpl(Integer.parseInt(version)));
            return createFileItem(history, createFileData(name, history));
        } catch (CommonException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean deleteHistory(FileData data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileData copyHistory(String srcName, FileData destData, String version) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Features supports() {
        return new FeaturesBuilder(this).build();
    }

    private FolderAPI getOrCreateProject(String name, boolean create) throws RRepositoryException {
        FolderAPI project;
        try {
            Node root = session.getRootNode();
            Node n = checkFolder(root, name, false);
            if (n != null) {
                project = new JcrFolderAPI(n,
                        new ArtefactPathImpl(new String[]{name.substring(name.lastIndexOf("/") + 1)}));
            } else if (create) {
                project = createArtifact(root, name);
            } else {
                project = null;
            }
        } catch (RepositoryException e) {
            throw new RRepositoryException("Failed to get an artifact ''{0}''", e, name);
        }
        return project;
    }

    private FolderAPI createArtifact(Node root, String path) throws RRepositoryException {
        try {
            int lastSeparator = path.lastIndexOf("/");
            Node parent;
            String name;
            if (lastSeparator >= 0) {
                String folder = path.substring(0, lastSeparator);
                name = path.substring(lastSeparator + 1);
                parent = checkFolder(root, folder, true);
            } else {
                name = path;
                parent = root;
            }
            Node node = NodeUtil.createNode(parent, name, JcrNT.NT_APROJECT, true);
            root.save();
            node.checkin();
            return new JcrFolderAPI(node, new ArtefactPathImpl(new String[]{name}));
        } catch (RepositoryException e) {
            throw new RRepositoryException("Failed to create an artifact ''{0}''", e, path);
        }
    }


    private FileData createFileData(String name, ArtefactAPI project) throws PropertyException {
        FileData fileData = new FileData();
        fileData.setName(name);

        // It's impossible to calculate zip size if project contains artefacts
        if (((FolderAPI) project).getArtefacts().isEmpty()) {
            fileData.setSize(0);
        }

        fileData.setDeleted(project.hasProperty(ArtefactProperties.PROP_PRJ_MARKED_4_DELETION));

        if (project.hasProperty(ArtefactProperties.VERSION_COMMENT)) {
            Property property = project.getProperty(ArtefactProperties.VERSION_COMMENT);
            fileData.setComment(property.getString());
        }

        ProjectVersion version = project.getVersion();
        fileData.setAuthor(version.getVersionInfo().getCreatedBy());
        fileData.setModifiedAt(version.getVersionInfo().getCreatedAt());
        fileData.setVersion(String.valueOf(version.getRevision()));
        return fileData;
    }

    private FileItem createFileItem(FolderAPI project, FileData fileData) throws IOException, ProjectException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(out);
        writeFolderToZip(project, zipOutputStream, "");
        zipOutputStream.close();

        return new FileItem(fileData, new ByteArrayInputStream(out.toByteArray()));
    }

    private void writeFolderToZip(FolderAPI folder,
                                  ZipOutputStream zipOutputStream,
                                  String pathPrefix) throws IOException, ProjectException {
        Collection<? extends ArtefactAPI> artefacts = folder.getArtefacts();
        for (ArtefactAPI artefact : artefacts) {
            if (artefact instanceof ResourceAPI) {
                ZipEntry entry = new ZipEntry(pathPrefix + artefact.getName());
                zipOutputStream.putNextEntry(entry);

                InputStream content = ((ResourceAPI) artefact).getContent();
                IOUtils.copy(content, zipOutputStream);

                content.close();
                zipOutputStream.closeEntry();
            } else {
                writeFolderToZip((FolderAPI) artefact, zipOutputStream, pathPrefix + artefact.getName() + "/");
            }
        }
    }

    private Node checkFolder(Node root, String aPath, boolean create) throws RepositoryException {
        Node node = root;
        String[] paths = aPath.split("/");
        for (String path : paths) {
            if (path.length() == 0) {
                continue; // first element (root folder) or illegal path
            }

            if (node.hasNode(path)) {
                // go deeper
                node = node.getNode(path);
            } else if (create) {
                // create new
                Node n = NodeUtil.createNode(node, path, JcrNT.NT_FOLDER, true);
                node.save();
                n.save();
                node = n;
            } else {
                return null;
            }
        }

        return node;
    }

    private ArtefactAPI getArtefact(String name) {
        try {
            Node node = checkFolder(session.getRootNode(), name, false);
            if (node == null) {
                return null;
            }

            return createArtefactAPI(node, name);
        } catch (RepositoryException e) {
            log.debug("Cannot get artefact " + name, e);
            return null;
        }
    }


    private ArtefactAPI createArtefactAPI(Node node, String name) throws RepositoryException {
        if (node.isNodeType(JcrNT.NT_LOCK)) {
            log.error("Incorrect node type " + JcrNT.NT_LOCK);
            return null;
        } else {
            ArtefactPathImpl path = new ArtefactPathImpl(name.split("/"));
            if (node.isNodeType(JcrNT.NT_FILE)) {
                return new JcrFileAPI(node, path);
            } else {
                return new JcrFolderAPI(node, path);
            }
        }
    }

    @Override
    public void close() {
        setListener(null);
        // Session can be null if failed to initialize repository. But this method can still be called during
        // finalization.
        if (session != null) {
            try {
                Workspace workspace = session.getWorkspace();
                ObservationManager manager = workspace.getObservationManager();
                manager.removeEventListener(this);
            } catch (RepositoryException e) {
                log.debug("release", e);
            }

            if (session.isLive()) {
                session.logout();
            }
        }
    }
}
