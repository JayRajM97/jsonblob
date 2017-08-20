package com.lowtuna.jsonblob.core;

import com.google.common.base.Stopwatch;
import io.dropwizard.util.Duration;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.DirectoryWalker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by tburch on 8/18/17.
 */
@AllArgsConstructor
@Slf4j
public class BlobCleanupProducer extends DirectoryWalker<Void> implements Runnable {
  private final Path dataDirectoryPath;
  private final Duration blobAccessTtl;
  private final BlockingQueue<File> filesToProcess;

  @Override
  protected boolean handleDirectory(File directory, int depth, Collection<Void> results) throws IOException {
    if (directory.listFiles() != null && directory.listFiles().length == 0) {
      if (directory.delete()) log.info("{} has no files, so it's being deleted", directory.getAbsolutePath());
      return false;
    }

    if (directory.listFiles().length == 1) {
      if (directory.listFiles()[0].getName().startsWith(FileSystemJsonBlobManager.BLOB_METADATA_FILE_NAME)) {
        if (directory.delete()) log.info("{} has only a metadata file, so it's being deleted", directory.getAbsolutePath());
        return false;
      }
    }

    boolean process = true;
    if (isDataDir(directory.getAbsolutePath())) {
      String[] dateParts = directory.getAbsolutePath().replace(dataDirectoryPath.toFile().getAbsolutePath(), "").split("/", 4);
      LocalDate localDate = LocalDate.of(Integer.parseInt(dateParts[1]), Integer.parseInt(dateParts[2]), Integer.parseInt(dateParts[3]));
      process = localDate.isBefore(LocalDate.now().minusDays(blobAccessTtl.toDays()));
      if (process) {
        log.info("Processing {} with {} blobs for un-accessed blobs", directory.getAbsolutePath(), directory.listFiles().length - 1);
        for (File file: directory.listFiles()) {
          try {
            filesToProcess.put(file);
          } catch (InterruptedException e) {
            log.warn("Interrupted while trying to add file to be processed at {}", file.getAbsolutePath(), e);
          }
        }
        process = false;
      }
    }

    return process;
  }

  private boolean isDataDir(String path) {
    return path.replace(dataDirectoryPath.toFile().getAbsolutePath(), "").split("/").length == 4;
  }

  @Override
  public void run() {
    Stopwatch stopwatch = new Stopwatch().start();
    try {
      walk(dataDirectoryPath.toFile(), null);
      log.info("Completed cleaning up un-accessed blobs in {}ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
