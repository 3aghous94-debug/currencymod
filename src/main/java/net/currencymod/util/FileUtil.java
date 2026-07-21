package net.currencymod.util;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.StringWriter;
import java.io.PrintWriter;

/**
 * Utility class for safe file operations
 */
public class FileUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("CurrencyMod/FileUtil");
    private static final String BACKUP_DIR = "currency_mod/backups";
    private static final DateTimeFormatter BACKUP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Ensure a directory exists, creating it if it doesn't
     *
     * @param directory The directory to check/create
     * @return true if the directory exists or was created, false otherwise
     */
    public static boolean ensureDirectoryExists(File directory) {
        try {
            if (directory.exists()) {
                if (!directory.isDirectory()) {
                    LOGGER.error("Path exists but is not a directory: {}", directory);
                    return false;
                }
                return true;
            }
            
            boolean created = directory.mkdirs();
            if (!created) {
                LOGGER.error("Failed to create directory: {}", directory);
                return false;
            }
            
            LOGGER.info("Created directory: {}", directory);
            return true;
        } catch (Exception e) {
            LOGGER.error("Error ensuring directory exists: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get a file in the server's run directory
     *
     * @param server The Minecraft server instance
     * @param relativePath The relative path from the run directory
     * @return The file object
     */
    public static File getServerFile(MinecraftServer server, String relativePath) {
        try {
            // In 1.21.1, getRunDirectory returns a Path, not a File
            Path runDir = server.getRunDirectory();
            Path filePath = runDir.resolve(relativePath);
            
            // Ensure parent directory exists
            File parentDir = filePath.getParent().toFile();
            ensureDirectoryExists(parentDir);
            
            return filePath.toFile();
        } catch (Exception e) {
            LOGGER.error("Error getting server file: {}", e.getMessage());
            // Fallback to current directory
            return new File(relativePath);
        }
    }

    /**
     * Create a backup of a file
     *
     * @param server The Minecraft server instance
     * @param originalFile The file to backup
     * @return true if backup was successful, false otherwise
     */
    public static boolean backupFile(MinecraftServer server, File originalFile) {
        if (!originalFile.exists() || !originalFile.isFile()) {
            LOGGER.debug("No file to backup at {}", originalFile);
            return false;
        }

        try {
            // In 1.21.1, getRunDirectory returns a Path, not a File
            Path runDir = server.getRunDirectory();
            Path backupDirPath = runDir.resolve(BACKUP_DIR);
            File backupDir = backupDirPath.toFile();
            
            if (!ensureDirectoryExists(backupDir)) {
                LOGGER.error("Could not create backup directory");
                return false;
            }
            
            String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
            String fileName = originalFile.getName();
            String backupFileName = fileName + "." + timestamp + ".bak";
            
            File backupFile = new File(backupDir, backupFileName);
            
            Files.copy(originalFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Created backup of {} at {}", originalFile, backupFile);
            
            return true;
        } catch (Exception e) {
            LOGGER.error("Error backing up file {}: {}", originalFile, e.getMessage());
            return false;
        }
    }

    /**
     * Safely write content to a file, creating a backup if the file already exists
     *
     * @param server The Minecraft server instance
     * @param file The file to write to
     * @param content The content to write
     * @return true if write was successful, false otherwise
     */
    public static boolean safeWriteToFile(MinecraftServer server, File file, String content) {
        try {
            // Log the file path we're trying to write to
            LOGGER.info("Attempting to write to file: {}", file.getAbsolutePath());
            
            // Safety check: don't write empty content that would erase existing data
            if ((content == null || content.trim().isEmpty()) && file.exists() && file.length() > 0) {
                LOGGER.warn("Prevented writing empty content to existing file: {}", file);
                return false;
            }
            
            // Verify we have permissions before attempting writes
            File parentDir = file.getParentFile();
            if (parentDir != null) {
                // Log parent directory state for debugging
                if (!parentDir.exists()) {
                    LOGGER.info("Parent directory {} does not exist, attempting to create", parentDir.getAbsolutePath());
                } else if (!parentDir.canWrite()) {
                    LOGGER.warn("Parent directory {} exists but is not writable", parentDir.getAbsolutePath());
                }
                
                // Create all parent directories
                if (!ensureDirectoryExists(parentDir)) {
                    LOGGER.error("Failed to create/access parent directory: {}", parentDir.getAbsolutePath());
                    return false;
                }
            }
            
            // If file exists, create a backup first
            if (file.exists()) {
                if (file.canWrite()) {
                    // N2-C-01 fix: the old code discarded backupFile's return
                    // value. If the backup failed silently (e.g. backup
                    // directory not writable, disk full), there was no
                    // recovery .bak either -- so the destructive delete at
                    // line 186 below would leave zero copies of the data if
                    // rename + copy both failed. We now abort if backup fails.
                    boolean backupOk = backupFile(server, file);
                    if (!backupOk) {
                        LOGGER.error("Failed to create backup of {}; aborting write to prevent potential data loss (N2-C-01)",
                            file.getAbsolutePath());
                        return false;
                    }
                } else {
                    LOGGER.error("Cannot write to existing file: {} (permissions denied)", file.getAbsolutePath());
                    return false;
                }
            }
            
            // Write to a temporary file first for atomic replacement
            File tempFile = new File(file.getAbsolutePath() + ".tmp");
            // N2-C-01 fix: track whether the temp file's contents have been
            // safely replicated to the target. The finally block below uses
            // this flag to decide whether it is safe to delete tempFile --
            // deleting tempFile while it still holds the ONLY copy of the
            // data (target file already deleted, rename failed, copy failed)
            // was the root cause of the silent-data-loss bug.
            boolean targetHasData = false;
            try {
                // Use FileOutputStream with explicit flush to ensure data is written
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(content);
                    writer.flush();
                    fos.flush();
                    fos.getFD().sync(); // Force synchronization with filesystem
                }
                
                // Verify the temp file was written successfully
                if (!tempFile.exists() || tempFile.length() == 0) {
                    LOGGER.error("Failed to write content to temp file: {}", tempFile);
                    tempFile.delete();
                    return false;
                }
                
                // N2-C-01 fix: try the rename FIRST without deleting the
                // original. File.renameTo on the same filesystem is atomic
                // and on POSIX replaces the destination atomically. On
                // Windows or cross-filesystem, renameTo may fail; only THEN
                // do we fall through to the destructive delete+copy path.
                // This preserves the original file as a recovery source if
                // the rename fails and the subsequent copy also fails.
                boolean renamed = tempFile.renameTo(file);
                
                if (renamed) {
                    targetHasData = true;
                    LOGGER.info("Successfully renamed temp file to {}", file);
                } else {
                    // Rename failed (likely cross-filesystem or target exists
                    // and the OS does not support atomic replace). Fall back
                    // to Files.copy with REPLACE_EXISTING, which on most
                    // platforms does an atomic replace if same filesystem.
                    LOGGER.warn("tempFile.renameTo failed (likely cross-filesystem or OS does not support atomic replace); falling back to Files.copy: {} -> {}",
                        tempFile, file);
                    try {
                        Files.copy(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        // Verify the copy succeeded by checking the target
                        // file's length matches the temp file's length.
                        if (file.exists() && file.length() == tempFile.length()) {
                            targetHasData = true;
                            LOGGER.info("Fallback Files.copy succeeded for {}", file);
                        } else {
                            LOGGER.error("Fallback Files.copy produced a target file with mismatched length " +
                                "(temp={}, target={}); treating as failure",
                                tempFile.length(), file.exists() ? file.length() : -1);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Fallback Files.copy also failed: {}", e.getMessage());
                    }
                }
                
                if (!targetHasData) {
                    // Both rename and copy failed. The original file is
                    // STILL INTACT (we never deleted it -- the old code did
                    // `file.delete()` before rename, which is what caused
                    // the data loss). Return false so the caller knows the
                    // write failed; the original data is preserved.
                    LOGGER.error("Failed to write to file {} by any method; original file is preserved (N2-C-01 fix)",
                        file.getAbsolutePath());
                    return false;
                }
                
                // Log success
                LOGGER.info("Successfully wrote to file {} ({} bytes)", file, file.length());
                return true;
            } finally {
                // N2-C-01 fix: only delete the temp file if we have
                // confirmed the target file has the data. If targetHasData
                // is false, the temp file may still be the only copy of the
                // data we just wrote (though in the current control flow we
                // return false above before reaching here, so this is
                // defensive). Keeping the temp file gives operators a chance
                // to manually recover the data.
                if (tempFile.exists() && targetHasData) {
                    if (!tempFile.delete()) {
                        LOGGER.warn("Failed to clean up temp file {} (target file is intact; this is non-fatal)",
                            tempFile.getAbsolutePath());
                    }
                } else if (tempFile.exists()) {
                    LOGGER.warn("Keeping temp file {} for manual recovery (target file write was not confirmed)",
                        tempFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error writing to file {}: {}", file, e.getMessage());
            // Print the full stack trace for diagnosis
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            LOGGER.error("Stack trace: {}", sw.toString());
            return false;
        }
    }

    /**
     * Safely read content from a file
     *
     * @param file The file to read from
     * @return The file content, or null if the file does not exist or cannot be read
     */
    public static String safeReadFromFile(File file) {
        if (!file.exists() || !file.isFile()) {
            LOGGER.debug("No file to read at {}", file);
            return null;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } catch (Exception e) {
            LOGGER.error("Error reading from file {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Check if a file is accessible for reading and/or writing
     *
     * @param file The file to check
     * @param needWrite Whether write permission is needed
     * @return true if the file is accessible, false otherwise
     */
    public static boolean isFileAccessible(File file, boolean needWrite) {
        try {
            // Check if parent directory exists and is accessible
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                LOGGER.debug("Parent directory does not exist for {}", file);
                return false;
            }
            
            // If file exists, check if it's readable and writable if needed
            if (file.exists()) {
                if (!file.canRead()) {
                    LOGGER.debug("File {} is not readable", file);
                    return false;
                }
                
                if (needWrite && !file.canWrite()) {
                    LOGGER.debug("File {} is not writable", file);
                    return false;
                }
                
                return true;
            }
            
            // If file doesn't exist but we need to write, check if we can create it
            if (needWrite) {
                if (parentDir != null && !parentDir.canWrite()) {
                    LOGGER.debug("Cannot write to parent directory of {}", file);
                    return false;
                }
            }
            
            return true;
        } catch (Exception e) {
            LOGGER.error("Error checking file accessibility {}: {}", file, e.getMessage());
            return false;
        }
    }
} 