package net.pms.medialibrary.commons.events;

import java.util.EventObject;

import net.pms.medialibrary.commons.dataobjects.DOManagedFile;
import net.pms.medialibrary.commons.enumarations.WatchedFileAction;

public class FileWatchActionEvent extends EventObject {
	private static final long serialVersionUID = -6461483325608601697L;
	private WatchedFileAction watchFileAction;
	private String filePath;
	private String oldFilePath;
	private DOManagedFile managedFile;

	public FileWatchActionEvent(Object source, WatchedFileAction watchFileAction,  DOManagedFile managedFile, String filePath, String oldFilePath) {
		super(source);
		
		this.watchFileAction = watchFileAction;
		this.managedFile = managedFile;
		this.filePath = filePath;
		this.oldFilePath = oldFilePath;
	}

	public WatchedFileAction getWatchFileAction() {
		return watchFileAction;
	}

	public String getFilePath() {
		return filePath;
	}

	public String getOldFilePath() {
		return oldFilePath;
	}

	public DOManagedFile getManagedFile() {
		return managedFile;
	}
}
