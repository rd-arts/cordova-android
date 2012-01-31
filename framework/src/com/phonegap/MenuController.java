package com.phonegap;

import android.util.Log;
import android.view.MenuItem;

public abstract class MenuController {
	
	private boolean mVisible=true;
	
	public void setVisible(boolean visible){
		Log.i("MenuController", "setVisible "+visible);
		mVisible=visible;
	}
	public boolean isVisible(){
		Log.i("MenuController", "isVisible "+mVisible);
		return mVisible;
	}
	
	public abstract boolean onCreateOptionsMenu(android.view.Menu menu);
	public abstract boolean onPrepareOptionsMenu(android.view.Menu menu);
	public abstract boolean onOptionsItemSelected(MenuItem item);
	
}