package com.codeborne.selenide.impl;

import com.codeborne.selenide.DownloadsFolder;
import com.codeborne.selenide.Driver;
import com.codeborne.selenide.Stopwatch;
import com.codeborne.selenide.ex.FileNotDownloadedError;
import com.codeborne.selenide.files.DownloadAction;
import com.codeborne.selenide.files.DownloadedFile;
import com.codeborne.selenide.files.FileFilter;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v122.browser.Browser;
import org.openqa.selenium.devtools.v122.browser.model.DownloadProgress;
import org.openqa.selenium.devtools.v122.browser.model.DownloadWillBegin;
import org.openqa.selenium.devtools.v122.page.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.codeborne.selenide.impl.FileHelper.moveFile;
import static com.codeborne.selenide.impl.WebdriverUnwrapper.cast;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static org.openqa.selenium.devtools.v122.browser.Browser.downloadProgress;
import static org.openqa.selenium.devtools.v122.browser.Browser.downloadWillBegin;

@ParametersAreNonnullByDefault
public class DownloadFileWithCdp {
  private static final Logger log = LoggerFactory.getLogger(DownloadFileWithCdp.class);
  private static final AtomicLong SEQUENCE = new AtomicLong();

  protected final Downloader downloader;

  DownloadFileWithCdp(Downloader downloader) {
    this.downloader = downloader;
  }

  public DownloadFileWithCdp() {
    this(new Downloader());
  }

  @Nullable
  protected DownloadsFolder getDownloadsFolder(Driver driver) {
    return driver.browserDownloadsFolder();
  }

  @CheckReturnValue
  @Nonnull
  public File download(WebElementSource anyClickableElement,
                       WebElement clickable, long timeout, long incrementTimeout,
                       FileFilter fileFilter,
                       DownloadAction action) {

    Driver driver = anyClickableElement.driver();
    DevTools devTools = initDevTools(driver);
    DownloadsFolder downloadsFolder = requireNonNull(getDownloadsFolder(driver), "Webdriver downloads folder is not configured");
    CdpDownloads downloads = new CdpDownloads(downloadsFolder, new ConcurrentHashMap<>(1));

    // Init download behaviour and listeners
    prepareDownloadWithCdp(driver, devTools, downloads, timeout);

    // Perform action an element that begins download process
    action.perform(anyClickableElement.driver(), clickable);

    try {
      // Wait until download
      File file = waitUntilDownloadsCompleted(anyClickableElement.driver(), fileFilter, timeout, incrementTimeout, downloads);

      //
      if (!fileFilter.match(new DownloadedFile(file, emptyMap()))) {
        String message = String.format("Failed to download file%s in %d ms.%s;%n actually downloaded: %s",
          fileFilter.description(), timeout, fileFilter.description(), file.getAbsolutePath());
        throw new FileNotDownloadedError(driver, message, timeout);
      }

      // Move file to unique folder
      return archiveFile(anyClickableElement.driver(), file);
    }
    finally {
      devTools.clearListeners();
    }
  }

  @Nonnull
  protected File archiveFile(Driver driver, File downloadedFile) {
    File uniqueFolder = downloader.prepareTargetFolder(driver.config());
    File archivedFile = new File(uniqueFolder, downloadedFile.getName());
    moveFile(downloadedFile, archivedFile);
    log.debug("Moved the downloaded file {} to {}", downloadedFile, archivedFile);
    return archivedFile;
  }

  private File waitUntilDownloadsCompleted(Driver driver, FileFilter fileFilter,
                                           long timeout, long incrementTimeout, CdpDownloads downloads) {
    long pollingInterval = Math.max(driver.config().pollingInterval(), 100);
    long downloadStartedAt = currentTimeMillis();
    Stopwatch stopwatch = new Stopwatch(timeout);
    do {
      Optional<CdpDownload> downloadedFile = downloads.find(fileFilter);
      if (downloadedFile.isPresent()) {
        log.debug("File {} download is complete after {} ms.", downloadedFile.get().fileName, stopwatch.getElapsedTimeMs());
        return downloadedFile.get().file();
      }
      else {
        failFastIfNoChanges(driver, downloads, fileFilter, downloadStartedAt, timeout, incrementTimeout);
      }
      stopwatch.sleep(pollingInterval);
    }
    while (!stopwatch.isTimeoutReached());

    String message = "Failed to download file%s in %d ms.".formatted(fileFilter.description(), timeout);
    throw new FileNotDownloadedError(driver, message, timeout);
  }

  private DevTools initDevTools(Driver driver) {
    if (driver.browser().isChromium()) {
      Optional<HasDevTools> cdpBrowser = cast(driver.getWebDriver(), HasDevTools.class);
      if (cdpBrowser.isPresent()) {
        DevTools devTools = cdpBrowser.get().getDevTools();
        devTools.createSessionIfThereIsNotOne();
        devTools.send(Page.enable());
        return devTools;
      }
    }
    throw new IllegalArgumentException("The browser you selected \"%s\" doesn't have Chrome Devtools protocol functionality."
      .formatted(driver.browser().name));
  }

  private void prepareDownloadWithCdp(Driver driver, DevTools devTools,
                                      CdpDownloads downloads,
                                      long timeout) {
    devTools.send(Browser.setDownloadBehavior(
      Browser.SetDownloadBehaviorBehavior.DEFAULT,
      Optional.empty(),
      Optional.empty(),
      Optional.of(true)));

    log.debug("clear devtools listeners");
    devTools.clearListeners();
    log.debug("add devtools listener for 'downloadWillBegin'");
    devTools.addListener(downloadWillBegin(), new DownloadWillBeginListener(id(), downloads));
    log.debug("add devtools listener for 'downloadProgress'");
    devTools.addListener(downloadProgress(), new DownloadProgressListener(id(), driver, downloads, timeout));
  }

  private record CdpDownloads(
    DownloadsFolder folder,
    ConcurrentMap<String, CdpDownload> downloads
  ) {
    private Optional<CdpDownload> find(FileFilter fileFilter) {
      return downloads.values().stream()
        .filter(download -> download.completed)
        .filter(download -> fileFilter.match(download.file()))
        .findAny();
    }

    private Optional<Long> lastModificationTime() {
      return downloads.values().stream().map(download -> download.lastModifiedAt).max(Long::compare);
    }

    private void addFile(String guid, String fileName) {
      downloads.put(guid, new CdpDownload(folder, fileName));
    }

    public void inProgress(String guid) {
      downloads.get(guid).lastModifiedAt = currentTimeMillis();
    }

    public void finish(String guid) {
      downloads.get(guid).completed = true;
    }
  }

  private static class CdpDownload {
    private final DownloadsFolder folder;
    private final String fileName;
    private long lastModifiedAt = currentTimeMillis();
    private boolean completed;

    private CdpDownload(DownloadsFolder folder, String fileName) {
      this.folder = folder;
      this.fileName = fileName;
    }

    private File file() {
      return new File(folder.getPath(), fileName);
    }
  }

  private static long id() {
    return SEQUENCE.incrementAndGet();
  }

  private record DownloadWillBeginListener(long id, CdpDownloads downloads) implements Consumer<DownloadWillBegin> {
    @Override
    public void accept(DownloadWillBegin e) {
      log.debug("[{}] Download will begin with suggested file name \"{}\" (url: \"{}\", frameId: {}, guid: {})",
        id, e.getSuggestedFilename(), e.getUrl(), e.getFrameId(), e.getGuid());
      downloads.addFile(e.getGuid(), e.getSuggestedFilename());
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "#" + id;
    }
  }

  private record DownloadProgressListener(long id, Driver driver, CdpDownloads downloads, long timeout)
    implements Consumer<DownloadProgress> {
    @Override
    public void accept(DownloadProgress e) {
      log.debug("[{}] Download is {} (received bytes: {}, total bytes: {}, guid: {})",
        id, e.getState(), e.getReceivedBytes(), e.getTotalBytes(), e.getGuid());

      switch (e.getState()) {
        case CANCELED -> {
          String message = "File download is %s (received bytes: %s, total bytes: %s, guid: %s)".formatted(
            e.getState(), e.getReceivedBytes(), e.getTotalBytes(), e.getGuid());
          throw new FileNotDownloadedError(driver, message, timeout);
        }
        case COMPLETED -> downloads.finish(e.getGuid());
        case INPROGRESS -> downloads.inProgress(e.getGuid());
      }
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "#" + id;
    }
  }

  private void failFastIfNoChanges(Driver driver, CdpDownloads downloads, FileFilter filter,
                                   long downloadStartedAt, long timeout, long incrementTimeout) {
    long now = currentTimeMillis();
    long lastModifiedAt = downloads.lastModificationTime().orElse(downloadStartedAt);
    long filesHasNotBeenUpdatedForMs = now - lastModifiedAt;
    if (filesHasNotBeenUpdatedForMs > incrementTimeout) {
      String message = String.format(
        "Failed to download file%s in %d ms: files in %s haven't been modified for %s ms. " +
        "(lastUpdate: %s, now: %s, incrementTimeout: %s)",
        filter.description(), timeout, downloads.folder, filesHasNotBeenUpdatedForMs,
        lastModifiedAt, now, incrementTimeout);
      throw new FileNotDownloadedError(driver, message, timeout);
    }
  }
}
