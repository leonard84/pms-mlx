package net.pms.medialibrary.watch;

import java.io.IOException;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.pms.medialibrary.commons.dataobjects.DOFileInfo;
import net.pms.medialibrary.commons.dataobjects.DOManagedFile;
import net.pms.medialibrary.commons.events.FileWatchActionEvent;
import net.pms.medialibrary.commons.events.FileWatchActionListener;
import net.pms.medialibrary.commons.exceptions.InitialisationException;
import net.pms.medialibrary.scanner.FullDataCollector;
import net.pms.medialibrary.storage.MediaLibraryStorage;

public class FolderWatcher implements Runnable {
	private static FolderWatcher instance;

	private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);
	private static int fileWatcherCounter = 0;
	
	private Map<DOManagedFile, ManagedFolderWatcher> watchFolders = new Hashtable<DOManagedFile, ManagedFolderWatcher>();
	
	private FileWatchActionListener fileWatchListener = new FileWatchActionListener() {
		
		@Override
		public void fileChanged(FileWatchActionEvent e) {
			switch (e.getWatchFileAction()) {
			case Create:
				log.info("Ne file detected : " + e.getFilePath());
				DOManagedFile fileToImport = e.getManagedFile();
				fileToImport.setPath(e.getFilePath());
				try {
					DOFileInfo ff = FullDataCollector.getInstance().get(e.getManagedFile());
					MediaLibraryStorage.getInstance().insertFileInfo(ff);
				} catch (InitialisationException e1) {
					log.error("Failed to collect data for file " + e.getFilePath());
				}
				break;
			case Delete:
				log.info("File deleted : " + e.getFilePath());
				MediaLibraryStorage.getInstance().deleteFileInfo(e.getFilePath());
				break;
			case Move:
				log.info(String.format("File moved: %s -> %s", e.getOldFilePath(), e.getFilePath()));
				MediaLibraryStorage.getInstance().updateFileInfoPath(e.getOldFilePath(), e.getFilePath());
				break;
			}
		}
	};
	
	public static FolderWatcher getInstance() {
		if(instance == null)  {
			instance = new FolderWatcher();
		}
		return instance;
	}
	
	public void addWatchFolder(DOManagedFile managedFile) {
		if(managedFile.isWatchEnabled()) {
			try {
				ManagedFolderWatcher wd = new ManagedFolderWatcher(managedFile);
				wd.addFileWatchActionListener(fileWatchListener);
				Thread th = new Thread(wd);
				th.setName("Watch" + fileWatcherCounter++);
				th.start();
			    watchFolders.put(managedFile, wd);
			    log.info(String.format("Started watching '%s'", managedFile.getPath()));
			} catch (IOException e) {
				log.error(String.format("Failed to add watch folder for '%s", managedFile.getPath()), e);
			}
		}
	}

	public void addWatchFolders(List<DOManagedFile> managedFolders) {
		for(DOManagedFile managedFolder : managedFolders) {
			addWatchFolder(managedFolder);
		}
	}
	
	public void removeWatchFolder(DOManagedFile managedFolder) {
		ManagedFolderWatcher watcher = watchFolders.remove(managedFolder);
		if(watcher != null) {
			stopFolderWatcher(watcher);
		}
	}
	
	public void clearWatchFolders() {
		if(watchFolders.size() > 0) {
			//stop the file watchers and remove them
			for(DOManagedFile watchFolder : watchFolders.keySet()) {
				stopFolderWatcher(watchFolders.get(watchFolder));
			}			
			watchFolders.clear();
		}
	}

	public void setWatchFolders(List<DOManagedFile> managedFolders) {
		clearWatchFolders();
		
		//set the new list
		for(DOManagedFile managedFolder : managedFolders) {
			addWatchFolder(managedFolder);
		}
	}
	
	private void stopFolderWatcher(ManagedFolderWatcher watcher) {
		watcher.removeFileWatchActionListener(fileWatchListener);
		watcher.stop();
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		
	}
}
