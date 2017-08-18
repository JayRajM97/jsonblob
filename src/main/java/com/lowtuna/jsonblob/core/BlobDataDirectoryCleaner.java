package com.lowtuna.jsonblob.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import io.dropwizard.util.Duration;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.DirectoryWalker;
import org.joda.time.DateTime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by tburch on 8/18/17.
 */
@AllArgsConstructor
@Slf4j
public class BlobDataDirectoryCleaner extends DirectoryWalker<String> implements Runnable {
  private final Path dataDirectoryPath;
  private final Duration blobAccessTtl;
  private final FileSystemJsonBlobManager fileSystemJsonBlobManager;
  private final ObjectMapper om;

  private final LoadingCache<String, BlobMetadataContainer> blobMetadataContainerCache = CacheBuilder.newBuilder()
          .expireAfterWrite(1, TimeUnit.HOURS)
          .build(new CacheLoader<String, BlobMetadataContainer>() {
            @Override
            public BlobMetadataContainer load(String key) throws Exception {
              File metadataFile = new File(key);
              return metadataFile.exists() ? om.readValue(fileSystemJsonBlobManager.readFile(metadataFile), BlobMetadataContainer.class) : new BlobMetadataContainer();
            }
          });

  @Override
  protected void handleFile(File file, int depth, Collection<String> results) throws IOException {
    log.debug("Processing {}", file.getAbsolutePath());
    String blobId = file.getName().split("\\.", 2)[0];
    File metadataFile = fileSystemJsonBlobManager.getMetaDataFile(file.getParentFile());

    if (file.equals(metadataFile)) {
      return;
    }

    BlobMetadataContainer metadataContainer = blobMetadataContainerCache.getUnchecked(metadataFile.getAbsolutePath());

    Optional<DateTime> lastAccessed = fileSystemJsonBlobManager.resolveTimestamp(blobId);
    if (metadataContainer.getLastAccessedByBlobId().containsKey(blobId)) {
      lastAccessed = Optional.of(metadataContainer.getLastAccessedByBlobId().get(blobId));
    }

    if (!lastAccessed.isPresent()) {
      log.warn("Couldn't get last accessed timestamp for blob {}", blobId);
      return;
    }

    log.debug("Blob {} was last accessed {}", blobId, lastAccessed.get());

    if (lastAccessed.get().plusMillis((int) blobAccessTtl.toMilliseconds()).isBefore(DateTime.now())) {
      log.info("Blob {} is older than {} (last accessed {}), so it's going to be deleted", blobId, blobAccessTtl, lastAccessed.get());
      file.delete();
      results.add(blobId);
    }
  }

  @Override
  protected boolean handleDirectory(File directory, int depth, Collection<String> results) throws IOException {
    if (directory.listFiles() != null && directory.listFiles().length == 0) {
      log.info("{} has no files, so it's being deleted", directory.getAbsolutePath());
      directory.delete();
      return false;
    }

    boolean process = true;
    if (isDataDir(directory.getAbsolutePath())) {
      String[] dateParts = directory.getAbsolutePath().replace(dataDirectoryPath.toFile().getAbsolutePath(), "").split("/", 4);
      LocalDate localDate = LocalDate.of(Integer.parseInt(dateParts[1]), Integer.parseInt(dateParts[2]), Integer.parseInt(dateParts[3]));
      process = localDate.isBefore(LocalDate.now().minusDays(blobAccessTtl.toDays()));
      if (process) {
        log.info("Processing {} for un-accessed blobs", directory.getAbsolutePath());
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
      List<String> removedBlobs = Lists.newArrayList();
      walk(dataDirectoryPath.toFile(), removedBlobs);
      log.info("Completed cleaning up {} un-accessed blobs in {}ms", removedBlobs.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
