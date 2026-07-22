package app.grapheneos.goscompat.securespawn.shared;

import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SecureSpawnFileDescriptorCheck {
    private static final String FRAMEWORK_TARGET = "/system/framework/framework.jar";
    private static final String SHARED_MEMORY_TARGET = "/dev/ashmem";
    private static final String MOUNT_ID_LABEL = "mnt_id:";
    private static final long FRAMEWORK_DETACHED_MOUNT_ID_LIMIT = 1024L;
    private static final long SHARED_MEMORY_DETACHED_MOUNT_ID_LIMIT = 1280L;

    private SecureSpawnFileDescriptorCheck() {
    }

    public static FileDescriptorState run() {
        Descriptor framework = findDescriptor(FRAMEWORK_TARGET);
        if (framework == null) {
            return new FileDescriptorState(
                    DescriptorState.absent(),
                    DescriptorState.absent(),
                    FRAMEWORK_DETACHED_MOUNT_ID_LIMIT,
                    SHARED_MEMORY_DETACHED_MOUNT_ID_LIMIT);
        }
        Set<Long> visibleMountIds = readVisibleMountIds();
        DescriptorState frameworkState = toState(framework, visibleMountIds);
        Descriptor sharedMemory = frameworkState.mountIdInMountInfo()
                ? findDescriptor(SHARED_MEMORY_TARGET) : null;
        return new FileDescriptorState(
                frameworkState,
                toState(sharedMemory, visibleMountIds),
                FRAMEWORK_DETACHED_MOUNT_ID_LIMIT,
                SHARED_MEMORY_DETACHED_MOUNT_ID_LIMIT);
    }

    private static DescriptorState toState(Descriptor descriptor, Set<Long> visibleMountIds) {
        if (descriptor == null) {
            return DescriptorState.absent();
        }
        return new DescriptorState(
                true,
                descriptor.sourceFd(),
                descriptor.mountId(),
                visibleMountIds.contains(descriptor.mountId()));
    }

    private static Descriptor findDescriptor(String targetPrefix) {
        File directory = new File("/proc/self/fd");
        String[] names = directory.list();
        if (names == null) {
            throw new IllegalStateException("Unable to list " + directory);
        }

        ArrayList<Integer> descriptors = new ArrayList<>(names.length);
        for (String name : names) {
            try {
                descriptors.add(Integer.parseInt(name));
            } catch (NumberFormatException ignored) {
                // procfs may grow non-numeric entries in the future.
            }
        }
        Collections.sort(descriptors);

        for (int sourceFd : descriptors) {
            ParcelFileDescriptor snapshot;
            try {
                snapshot = ParcelFileDescriptor.fromFd(sourceFd);
            } catch (IOException e) {
                // Ignore descriptors which closed after the directory snapshot.
                continue;
            }

            try (snapshot) {
                int snapshotFd = snapshot.getFd();
                File snapshotLink = new File(directory, Integer.toString(snapshotFd));
                String target = Files.readSymbolicLink(snapshotLink.toPath()).toString();
                if (target.startsWith(targetPrefix)) {
                    return new Descriptor(sourceFd, readMountId(snapshotFd));
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unable to inspect descriptor snapshot for fd " + sourceFd, e);
            }
        }

        return null;
    }

    private static long readMountId(int fd) throws IOException {
        File fdinfo = new File("/proc/self/fdinfo/" + fd);
        try (BufferedReader reader = new BufferedReader(new FileReader(fdinfo))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(MOUNT_ID_LABEL)) {
                    return parseMountId(line.substring(MOUNT_ID_LABEL.length()), fdinfo);
                }
            }
        }
        throw new IllegalStateException("Mount ID not found in " + fdinfo);
    }

    private static Set<Long> readVisibleMountIds() {
        File mountinfo = new File("/proc/self/mountinfo");
        HashSet<Long> result = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(mountinfo))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf(' ');
                if (separator <= 0) {
                    throw new IllegalStateException("Malformed line in " + mountinfo);
                }
                result.add(parseMountId(line.substring(0, separator), mountinfo));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + mountinfo, e);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No mount IDs found in " + mountinfo);
        }
        return result;
    }

    private static long parseMountId(String value, File source) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid mount ID in " + source, e);
        }
    }

    private record Descriptor(int sourceFd, long mountId) {}

    public record DescriptorState(
            boolean present,
            int fd,
            long mountId,
            boolean mountIdInMountInfo) {
        private static DescriptorState absent() {
            return new DescriptorState(false, -1, -1, false);
        }
    }

    public record FileDescriptorState(
            DescriptorState framework,
            DescriptorState sharedMemory,
            long frameworkDetachedMountIdLimit,
            long sharedMemoryDetachedMountIdLimit) {
        public boolean hasDetachedMountIdRegression() {
            if (!framework().present()) {
                return false;
            }
            if (!framework().mountIdInMountInfo()) {
                return framework().mountId() > frameworkDetachedMountIdLimit();
            }
            return sharedMemory().present()
                    && !sharedMemory().mountIdInMountInfo()
                    && sharedMemory().mountId() > sharedMemoryDetachedMountIdLimit();
        }

        @Override
        public String toString() {
            return "frameworkDescriptorPresent=" + framework().present()
                    + "\nframeworkFd=" + framework().fd()
                    + "\nframeworkMountId=" + framework().mountId()
                    + "\nframeworkMountIdInMountInfo=" + framework().mountIdInMountInfo()
                    + "\nsharedMemoryDescriptorPresent=" + sharedMemory().present()
                    + "\nsharedMemoryFd=" + sharedMemory().fd()
                    + "\nsharedMemoryMountId=" + sharedMemory().mountId()
                    + "\nsharedMemoryMountIdInMountInfo=" + sharedMemory().mountIdInMountInfo()
                    + "\nframeworkDetachedMountIdLimit=" + frameworkDetachedMountIdLimit()
                    + "\nsharedMemoryDetachedMountIdLimit=" + sharedMemoryDetachedMountIdLimit()
                    + "\nhasDetachedMountIdRegression=" + hasDetachedMountIdRegression();
        }
    }
}
