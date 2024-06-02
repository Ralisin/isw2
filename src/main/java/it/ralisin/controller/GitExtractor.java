package it.ralisin.controller;

import it.ralisin.entities.JavaClass;
import it.ralisin.entities.Release;
import it.ralisin.tools.GitFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GitExtractor {
    final GitFactory gitFactory;

    public GitExtractor(String projName, String repoURL) {
        this.gitFactory = new GitFactory(projName, repoURL);
    }

    public void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        boolean deleted = directory.delete();
        if (!deleted) {
            // Log failure to delete the directory
            Logger.getAnonymousLogger().log(Level.INFO, "Could not delete " + directory);
        }
    }

    public List<RevCommit> getAllCommits() throws IOException, GitAPIException {
        List<RevCommit> commitList = new ArrayList<>();

        Git git = gitFactory.getGit();

        // Get all commits
        try (Repository ignored = git.getRepository()) {
            Iterable<RevCommit> allCommits = git.log().all().call();
            for (RevCommit commit : allCommits) {
                commitList.add(commit);
            }
        }

        return commitList;
    }

    public void extractJavaFiles(List<Release> releaseList) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(releaseList.size());
        List<Callable<Void>> tasks = new ArrayList<>();

        System.out.println("Extracting Java Files");

        for(Release release : releaseList) {

            tasks.add(() -> {
                List<JavaClass> touchedJavaClassList = release.getJavaClassList();
                Set<String> classPathSet = new HashSet<>();

                processCommitsForRelease(release, touchedJavaClassList, classPathSet);

                return null;
            });
        }

        executor.invokeAll(tasks);
        executor.shutdown();
    }

    private void processCommitsForRelease(Release release, List<JavaClass> touchedJavaClassList, Set<String> classPathSet) throws IOException, GitAPIException {
        int commitCount = 0;
        for (RevCommit commit : release.getCommitList()) {
            System.out.print("\r\tRevCommit (" + commitCount + ", " + release.getCommitList().size() + ")");
            commitCount++;

            ObjectId treeId = commit.getTree();
            try (TreeWalk treeWalk = new TreeWalk(gitFactory.getGit().getRepository())) {
                treeWalk.reset(treeId);
                treeWalk.setRecursive(true);
                processTreeWalk(treeWalk, commit, touchedJavaClassList, classPathSet);
            }
        }
    }

//    private void processTreeWalk(TreeWalk treeWalk, RevCommit commit, List<JavaClass> touchedJavaClassList, Set<String> classPathSet) throws IOException, GitAPIException {
//        while (treeWalk.next()) {
//            String classPath = treeWalk.getPathString();
//
//            if (classPath.endsWith(".java") && !classPath.contains("/test/")) {
//                int addedLines = 0;
//                int deletedLines = 0;
//                boolean isFix = isFixed(commit);
//                String author = commit.getAuthorIdent().getName();
//
//                JavaClass javaClass;
//                RevCommit prevCommit;
//                if (classPathSet.add(classPath)) {
//                    String fileContent = getFileContent(commit, classPath);
//                    javaClass = new JavaClass(classPath, fileContent);
//
//                    if (checkCommitTouchesClass(commit, classPath)) {
//                        prevCommit = getPreviousCommit(commit.getName(), classPath);
//                        if (prevCommit != null) {
//                            // Conta le righe aggiunte e tolte
//                            int[] counts = countAddedAndDeletedLines(gitFactory.getGit().getRepository(), prevCommit, commit, classPath);
//                            addedLines = counts[0];
//                            deletedLines = counts[1];
//                        }
//
//                        javaClass.addCommit(commit, addedLines, deletedLines, isFix, author);
//                    }
//
//                    touchedJavaClassList.add(javaClass);
//                } else {
//                    javaClass = getExistingJavaClass(touchedJavaClassList, commit, classPath);
//
//                    prevCommit = getPreviousCommit(commit.getName(), classPath);
//                    if (prevCommit != null) {
//                        // Conta le righe aggiunte e tolte
//                        int[] counts = countAddedAndDeletedLines(gitFactory.getGit().getRepository(), prevCommit, commit, classPath);
//                        addedLines = counts[0];
//                        deletedLines = counts[1];
//                    }
//
//                    if (javaClass != null)
//                        javaClass.addCommit(commit, addedLines, deletedLines, isFix, author);
//                }
//            }
//        }
//    }

    private void processTreeWalk(TreeWalk treeWalk, RevCommit commit, List<JavaClass> touchedJavaClassList, Set<String> classPathSet) throws IOException, GitAPIException {
        while (treeWalk.next()) {
            String classPath = treeWalk.getPathString();

            if (shouldProcessClass(classPath)) {
                JavaClass javaClass;
                if (classPathSet.add(classPath)) {
                    javaClass = processNewJavaClass(commit, classPath);
                    touchedJavaClassList.add(javaClass);
                } else {
                    javaClass = getExistingJavaClass(touchedJavaClassList, commit, classPath);
                    updateExistingJavaClass(javaClass, commit, classPath);
                }
            }
        }
    }

    private boolean shouldProcessClass(String classPath) {
        return classPath.endsWith(".java") && !classPath.contains("/test/");
    }

    private JavaClass processNewJavaClass(RevCommit commit, String classPath) throws IOException, GitAPIException {
        String fileContent = getFileContent(commit, classPath);
        JavaClass javaClass = new JavaClass(classPath, fileContent);

        if (checkCommitTouchesClass(commit, classPath)) {
            addCommitToJavaClass(javaClass, commit, classPath);
        }

        return javaClass;
    }

    private void updateExistingJavaClass(JavaClass javaClass, RevCommit commit, String classPath) throws IOException, GitAPIException {
        if (javaClass != null) {
            addCommitToJavaClass(javaClass, commit, classPath);
        }
    }

    private void addCommitToJavaClass(JavaClass javaClass, RevCommit commit, String classPath) throws IOException, GitAPIException {
        RevCommit prevCommit = getPreviousCommit(commit.getName(), classPath);
        if (prevCommit != null) {
            int[] counts = countAddedAndDeletedLines(gitFactory.getGit().getRepository(), prevCommit, commit, classPath);
            int addedLines = counts[0];
            int deletedLines = counts[1];

            boolean isFix = isFixed(commit);
            String author = commit.getAuthorIdent().getName();

            javaClass.addCommit(commit, addedLines, deletedLines, isFix, author);
        }
    }

    private String getFileContent(RevCommit commit, String filePath) throws IOException, GitAPIException {
        Git git = gitFactory.getGit();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            treeWalk.setFilter(PathFilter.create(filePath));
            while (treeWalk.next()) {
                if (!treeWalk.isSubtree() && treeWalk.getPathString().equals(filePath)) {
                    git.getRepository().open(treeWalk.getObjectId(0)).copyTo(out);

                    return out.toString();
                }
            }
        }

        return "";
    }

    private JavaClass getExistingJavaClass(List<JavaClass> touchedJavaClassList, RevCommit commit, String classPath) throws GitAPIException, IOException {
        for (JavaClass javaClass : touchedJavaClassList) {
            if (javaClass.getClassPath().equals(classPath) && checkCommitTouchesClass(commit, classPath)) {
                if (checkCommitTouchesClass(commit, classPath)) {
                    return javaClass;
                }

                break;
            }
        }

        return null;
    }

    private boolean checkCommitTouchesClass(RevCommit commit, String classFilePath) throws IOException, GitAPIException {
        try (Repository repository = gitFactory.getGit().getRepository();
             Git git = new Git(repository);
             RevWalk revWalk = new RevWalk(repository)) {

            RevCommit parentCommit = revWalk.parseCommit(commit.getParent(0).getId());

            // Get treeParser for the commit and his parent
            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            oldTreeParser.reset(repository.newObjectReader(), parentCommit.getTree().getId());
            CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
            newTreeParser.reset(repository.newObjectReader(), commit.getTree().getId());

            // Get difference list through commit and his parent
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(oldTreeParser)
                    .setNewTree(newTreeParser)
                    .call();

            // Check if any of the differences involve the class file
            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(classFilePath)) {
                    return true;
                }
            }
        }

        return false;
    }

    public RevCommit getPreviousCommit(String commitId, String filePath) throws IOException {
        try (Git git = gitFactory.getGit()) {
            Repository repository = git.getRepository();

            ObjectId commitObjectId = repository.resolve(commitId);
            if (commitObjectId == null) return null;

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit targetCommit = revWalk.parseCommit(commitObjectId);
                revWalk.markStart(targetCommit);

                // Aggiungi un filtro per il percorso specifico
                revWalk.setTreeFilter(PathFilter.create(filePath));

                RevCommit previousCommit = null;
                for (RevCommit commit : revWalk) {
                    if (commit.equals(targetCommit)) continue;

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (DiffFormatter diffFormatter = new DiffFormatter(out)) {
                        diffFormatter.setRepository(repository);
                        List<DiffEntry> diffs = diffFormatter.scan(commit.getTree(), targetCommit.getTree());

                        for (DiffEntry diff : diffs) {
                            if (diff.getNewPath().equals(filePath) || diff.getOldPath().equals(filePath)) {
                                previousCommit = commit;
                                break;
                            }
                        }
                    }

                    if (previousCommit != null) break;
                }

                return previousCommit;
            }
        } catch (GitAPIException e) {
            return null;
        }
    }

    private static int[] countAddedAndDeletedLines(Repository repository, RevCommit oldCommit, RevCommit newCommit, String filePath) throws IOException {
        int addedLines = 0;
        int deletedLines = 0;

        try (DiffFormatter diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
            diffFormatter.setRepository(repository);

            List<DiffEntry> diffs = diffFormatter.scan(oldCommit.getTree(), newCommit.getTree());
            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(filePath) || diff.getOldPath().equals(filePath)) {
                    EditList editList = diffFormatter.toFileHeader(diff).toEditList();
                    for (Edit edit : editList) {
                        addedLines += edit.getEndB() - edit.getBeginB();
                        deletedLines += edit.getEndA() - edit.getBeginA();
                    }
                }
            }
        }

        return new int[]{addedLines, deletedLines};
    }

    public boolean isFixed(RevCommit commit) {
        String commitMessage = commit.getFullMessage().toLowerCase();
        return commitMessage.contains("fix");
    }
}
