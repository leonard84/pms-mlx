package net.pms.medialibrary.watch;

/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Oracle nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;
import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;

import net.pms.medialibrary.commons.dataobjects.DOManagedFile;
import net.pms.medialibrary.commons.enumarations.WatchedFileAction;
import net.pms.medialibrary.commons.events.FileWatchActionEvent;
import net.pms.medialibrary.commons.events.FileWatchActionListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example to watch a directory (or tree) for changes to files.
 * <
 */
public class ManagedFolderWatcher implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(ManagedFolderWatcher.class);
	
	private final Map<WatchKey, Path> keys;	
	private DOManagedFile managedFolder;
	private List<FileWatchActionListener> fileWatchActionListeners = new ArrayList<>();
	private boolean isRunning;
	
	private WatchService watcher;
	private static EventEntry eventEntry;
	private static Object eventEntrySyncObj = new Object();

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

	/**
	 * Creates a WatchService and registers the given directory
	 */
	public ManagedFolderWatcher(DOManagedFile managedFolder) throws IOException {
		this.managedFolder = managedFolder;

		Path dir = Paths.get(managedFolder.getPath());
		this.keys = new HashMap<WatchKey, Path>();
		try {
			watcher = FileSystems.getDefault().newWatchService();
			if (managedFolder.isSubFoldersEnabled()) {
				log.debug(String.format("Scanning %s ...", dir));
				registerAll(dir);
				log.debug("Done scanning.");
			} else {
				register(dir);
			}
			
		} catch (IOException e) {
			log.error("Failed to initialize watch service", e);
		}
	}
	
	public void addFileWatchActionListener(FileWatchActionListener listener) {
		fileWatchActionListeners.add(listener);
	}
	
	public void removeFileWatchActionListener(FileWatchActionListener listener) {
		fileWatchActionListeners.remove(listener);
	}

	/**
	 * Register the given directory with the WatchService
	 */
	private void register(Path dir) throws IOException {
		WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
		if (log.isDebugEnabled()) {
			Path prev = keys.get(key);
			if (prev == null) {
				log.debug(String.format("Start watching folder: %s", dir));
			} else {
				if (!dir.equals(prev)) {
					log.debug(String.format("Update watch folder: %s -> %s", prev, dir));
				}
			}
		}
		keys.put(key, dir);
	}

	/**
	 * Register the given directory, and all its sub-directories, with the
	 * WatchService.
	 */
	private void registerAll(final Path start) throws IOException {
		// register directory and sub-directories
		Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir,
					BasicFileAttributes attrs) throws IOException {
				register(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
	
	public boolean isProcessingEvents() {
		return isRunning;
	}

	public void stop() {
		try {
			isRunning = false;
			watcher.close();
		} catch (IOException e) {
			log.error("Failed to close watcher", e);
		}
		fileWatchActionListeners.clear();
	}

	@Override
	public void run() {
		isRunning = true;
		while(true) {

			// wait for key to be signalled
			WatchKey key;
			try {
				key = watcher.take();
			} catch (InterruptedException x) {
				break;
			} catch (ClosedWatchServiceException x) {
				break;
			}

			Path dir = keys.get(key);
			if (dir == null) {
				log.error(String.format("WatchKey not recognized! key='%s'", key));
				continue;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				Kind<?> kind = event.kind();

				// TBD - provide example of how OVERFLOW event is handled
				if (kind == OVERFLOW) {
					continue;
				}

				// Context for directory entry event is the file name of entry
				WatchEvent<Path> ev = cast(event);
				Path name = ev.context();
				Path child = dir.resolve(name);

				// print out event
				log.debug(String.format("%s: %s", event.kind().name(), child));
				if(eventEntry == null) {
					List<Kind<?>> kinds = new ArrayList<>();
					kinds.add(event.kind());
					eventEntry = new EventEntry(kinds, child.toString(), child.toString());
				} else {
					eventEntry.events.add(event.kind());
					eventEntry.oldPath = child.toString();
				}
				
				//do delay the check a bit to have time to get some messages to allow single file operations.
				//Figure out a way to handle this list properly if e.g. a folder is being renamed or multiple files being moved
				
//				Win7 sequences:
//				Create:
//				[            Watch9] DEBUG 2012-04-05 21:18:46.060 ENTRY_CREATE: C:\tmp\test (2011) - Copy.mkv
//				[            Watch9] DEBUG 2012-04-05 21:18:46.061 ENTRY_MODIFY: C:\tmp\test (2011) - Copy.mkv
//				[            Watch9] DEBUG 2012-04-05 21:19:49.779 ENTRY_MODIFY: C:\tmp\test (2011) - Copy.mkv
//
//				Move in same watch folder:
//				[            Watch9] DEBUG 2012-04-05 21:20:27.952 ENTRY_DELETE: C:\tmp\test (2011) - Copy.mkv
//				[            Watch9] DEBUG 2012-04-05 21:20:27.953 ENTRY_CREATE: C:\tmp\tmp\test (2011) - Copy.mkv
//				[            Watch9] DEBUG 2012-04-05 21:20:27.953 ENTRY_MODIFY: C:\tmp\tmp\test (2011) - Copy.mkv
//				[            Watch9] DEBUG 2012-04-05 21:20:27.953 ENTRY_MODIFY: C:\tmp\tmp
//
//				Move to other watch folder:
//				[            Watch5] DEBUG 2012-04-06 16:11:47.533 ENTRY_CREATE: C:\tmp\test (2011).mkv
//				[            Watch5] DEBUG 2012-04-06 16:11:47.544 ENTRY_MODIFY: C:\tmp\test (2011).mkv
//				[            Watch5] DEBUG 2012-04-06 16:12:15.707 ENTRY_MODIFY: C:\tmp\test (2011).mkv
//				[            Watch0] DEBUG 2012-04-06 16:12:15.912 ENTRY_DELETE: D:\tmp\test (2011).mkv
//
//				Rename:
//				[            Watch9] DEBUG 2012-04-05 21:21:34.143 ENTRY_DELETE: C:\tmp\test (2011).mkv
//				[            Watch9] DEBUG 2012-04-05 21:21:34.144 ENTRY_CREATE: C:\tmp\test ().mkv
//				[            Watch9] DEBUG 2012-04-05 21:21:34.145 ENTRY_MODIFY: C:\tmp\test ().mkv
//
//				Delete:
//				[            Watch9] DEBUG 2012-04-05 21:22:02.921 ENTRY_DELETE: C:\tmp\test (2011).mkv
				Thread delaidCheck = new Thread(new Runnable() {					
					@Override
					public void run() {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							//do nothing;
						}
						checkEvents();
					}
				});
				delaidCheck.setName("DelayedCheck");
				delaidCheck.start();

				// if directory is created, and watching recursively, then
				// register it and its sub-directories
				if (managedFolder.isSubFoldersEnabled() && (kind == ENTRY_CREATE)) {
					try {
						if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
							registerAll(child);
						}
					} catch (IOException x) {
						log.error(String.format("Failed to register folder %s with all of its children", child));
					}
				}
			}

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				keys.remove(key);

				// all directories are inaccessible
				if (keys.isEmpty()) {
					break;
				}
			}
		}
		isRunning = false;
	}

	private void checkEvents() {
		synchronized (eventEntrySyncObj) {
			if (eventEntry != null) {
				List<Kind<?>> events = eventEntry.events;
	
				if (events.size() > 3) {
					if (events.get(0).equals(ENTRY_CREATE)
						&& events.get(1).equals(ENTRY_MODIFY)
						&& events.get(2).equals(ENTRY_MODIFY)
						&& events.get(3).equals(ENTRY_DELETE)) {
						fireFileWatchAction(WatchedFileAction.Move, eventEntry.path, eventEntry.oldPath);
					}
				} else if (events.size() > 2) {
					if (events.get(0).equals(ENTRY_CREATE)
							&& events.get(1).equals(ENTRY_MODIFY)
							&& events.get(2).equals(ENTRY_MODIFY)) {
						fireFileWatchAction(WatchedFileAction.Create, eventEntry.path, null);
					} else if ((events.get(0).equals(ENTRY_DELETE)
								&& events.get(1).equals(ENTRY_CREATE)
								&& events.get(2).equals(ENTRY_MODIFY)) 
							|| (events.size() > 3 
								&& events.get(0).equals(ENTRY_CREATE)
								&& events.get(1).equals(ENTRY_MODIFY)
								&& events.get(2).equals(ENTRY_MODIFY)
								&& events.get(3).equals(ENTRY_DELETE))) {
						fireFileWatchAction(WatchedFileAction.Move, eventEntry.path, eventEntry.oldPath);
					}	
				} else if (events.size() == 1) {
					if (events.get(0).equals(ENTRY_DELETE)) {
						fireFileWatchAction(WatchedFileAction.Delete, eventEntry.path, null);
					}
				}
			}
		}
	}
	
	private void fireFileWatchAction(WatchedFileAction watchFileAction, String filePath, String oldFilePath) {
		for(FileWatchActionListener l : fileWatchActionListeners) {
			l.fileChanged(new FileWatchActionEvent(this, watchFileAction, managedFolder, filePath, oldFilePath));
		}
		eventEntry = null;
	}
	
	private class EventEntry {
		private List<Kind<?>> events;
		private String path;
		private String oldPath;
		
		public EventEntry(List<Kind<?>> events, String path, String oldPath)  {
			this.events = events;
			this.path = path;
			this.oldPath = oldPath;
		}
	}
}