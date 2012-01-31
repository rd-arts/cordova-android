package com.phonegap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.xmlpull.v1.XmlPullParserException;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions.Callback;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import com.phonegap.api.IPlugin;
import com.phonegap.api.LOG;
import com.phonegap.api.PluginManager;

public class GapView extends WebView {

	public static final String TAG = "GapView";
	
	private MenuController mMenuController;

	private Activity mActivity;
	private boolean classicRender;
	private ArrayList<Pattern> whiteList = new ArrayList<Pattern>();
    public CallbackServer callbackServer;
    private HashMap<String, Boolean> whiteListCache = new HashMap<String,Boolean>();
    protected PluginManager pluginManager;
    protected boolean cancelLoadUrl = false;
    private String url = null;
    private Stack<String> urls = new Stack<String>();
    protected WebViewClient webViewClient;
    protected ProgressDialog spinnerDialog = null;
    public boolean bound = false;
    
    // Plugin to call when activity result is received
    protected IPlugin activityResultCallback = null;

	// preferences read from phonegap.xml
	protected PreferenceSet preferences;

    // The base of the initial URL for our app.
    // Does not include file name.  Ends with /
    // ie http://server/path/
    private String baseUrl = null;
    
    // Flag indicates that a loadUrl timeout occurred
    private int loadUrlTimeout = 0;
    
    // LoadUrl timeout value in msec (default of 20 sec)
    protected int loadUrlTimeoutValue = 20000;
    
	public GapView(Context context, AttributeSet attrs) {
		super(context, attrs);
		preferences = new PreferenceSet();
		// Load PhoneGap configuration:
		// white list of allowed URLs
		// debug setting
		loadConfiguration();
		init();
		LOG.d(TAG, "GapView Contructor");
	}
	
	public void setMenuController(MenuController menuController){
		mMenuController = menuController;
	}
	public MenuController getMenuController(){
		return mMenuController;
	}
	
	public void resetLoadUrlTimeout(){
		loadUrlTimeout++;
	}
	
	public String getBaseUrl(){
		return baseUrl;
	}

    /**
     * Load the url into the webview after waiting for period of time.
     * This is used to display the splashscreen for certain amount of time.
     * 
     * @param url
     * @param time              The number of ms to wait before loading webview
     */
    public void loadUrl(final String url, final int time) {

        // Clear cancel flag
        this.cancelLoadUrl = false;
        
        // If not first page of app, then load immediately
        if (this.urls.size() > 0) {
            this.loadUrl(url);
        }
        
        if (!url.startsWith("javascript:")) {
            LOG.d(TAG, "DroidGap.loadUrl(%s, %d)", url, time);
        }
        final GapView me = this;

        Runnable runnable = new Runnable() {
            public void run() {
                try {
                    synchronized(this) {
                        this.wait(time);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (!me.cancelLoadUrl) {
                    me.loadUrl(url);
                }
                else{
                    me.cancelLoadUrl = false;
                    LOG.d(TAG, "Aborting loadUrl(%s): Another URL was loaded before timer expired.", url);
                }
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();
    }
    
    /**
     * Cancel loadUrl before it has been loaded.
     */
    public void cancelLoadUrl() {
        this.cancelLoadUrl = true;
    }
    
    /**
     * Clear the resource cache.
     */
    public void clearCache() {
        this.clearCache(true);
    }

    /**
     * Clear web history in this web view.
     */
    public void clearHistory() {
        this.urls.clear();
        super.clearHistory();
        
        // Leave current url on history stack
        if (this.url != null) {
            this.urls.push(this.url);
        }
    }
    
    /**
     * Go to previous page in history.  (We manage our own history)
     * 
     * @return true if we went back, false if we are already at top
     */
    public boolean backHistory() {

        // Check webview first to see if there is a history
        // This is needed to support curPage#diffLink, since they are added to appView's history, but not our history url array (JQMobile behavior)
        if (this.canGoBack()) {
            this.goBack();  
            return true;
        }

        // If our managed history has prev url
        if (this.urls.size() > 1) {
            this.urls.pop();                // Pop current url
            String url = this.urls.pop();   // Pop prev url that we want to load, since it will be added back by loadUrl()
            this.loadUrl(url);
            return true;
        }
        
        return false;
    }

    /**
     * Load the url into the webview.
     * 
     * @param url
     */
    public void loadUrl(final String url) {
        if (!url.startsWith("javascript:")) {
            LOG.d(TAG, "DroidGap.loadUrl(%s)", url);
        }

        this.url = url;
        if (this.baseUrl == null) {
            int i = url.lastIndexOf('/');
            if (i > 0) {
                this.baseUrl = url.substring(0, i+1);
            }
            else {
                this.baseUrl = this.url + "/";
            }
        }
        if (!url.startsWith("javascript:")) {
            LOG.d(TAG, "DroidGap: url=%s baseUrl=%s", url, baseUrl);
        }
        
        // Load URL on UI thread
        final GapView me = this;

        // Track URLs loaded instead of using appView history
        me.urls.push(url);
        me.clearHistory();
    
        // Create callback server and plugin manager
        if (me.callbackServer == null) {
            me.callbackServer = new CallbackServer();
            me.callbackServer.init(url);
        }
        else {
            me.callbackServer.reinit(url);
        }
        if (me.pluginManager == null) {
            me.pluginManager = new PluginManager(me);        
        }
        
        if (!url.startsWith("javascript:")) {
            // Create a timeout timer for loadUrl
            final int currentLoadUrlTimeout = me.loadUrlTimeout;
            Runnable runnable = new Runnable() {
                public void run() {
                    try {
                        synchronized(this) {
                            wait(me.loadUrlTimeoutValue);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    
                    // If timeout, then stop loading and handle error
                    if (me.loadUrlTimeout == currentLoadUrlTimeout) {
                        me.stopLoading();
                        LOG.e(TAG, "DroidGap: TIMEOUT ERROR! - calling webViewClient");
                        me.webViewClient.onReceivedError(me, -6, "The connection to the server was unsuccessful.", url);
                    }
                }
            };
            Thread thread = new Thread(runnable);
            thread.start();
        }
        super.loadUrl(url);
    }
    
	/**
	 * Initialize web container.
	 */
	public void init() {
		LOG.d(TAG, "GapView.init()");

		this.setWebChromeClient(new GapClient(this));
		this.setWebViewClient(new GapWebViewClient(this));

		// 14 is Ice Cream Sandwich!
		if (android.os.Build.VERSION.SDK_INT < 14 && this.classicRender) {
			// This hack fixes legacy PhoneGap apps
			// We should be using real pixels, not pretend pixels
			final float scale = getResources().getDisplayMetrics().density;
			int initialScale = (int) (scale * 100);
			this.setInitialScale(initialScale);
		} else {
			this.setInitialScale(0);
		}
		this.setVerticalScrollBarEnabled(false);
		this.requestFocusFromTouch();

		// Enable JavaScript
		WebSettings settings = this.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setJavaScriptCanOpenWindowsAutomatically(true);
		settings.setLayoutAlgorithm(LayoutAlgorithm.NORMAL);

		// Set the nav dump for HTC
		settings.setNavDump(true);

		// Enable database
		settings.setDatabaseEnabled(true);
		String databasePath = this.getApplicationContext().getApplicationContext()
				.getDir("database", Context.MODE_PRIVATE).getPath();
		settings.setDatabasePath(databasePath);

		// Enable DOM storage
		settings.setDomStorageEnabled(true);

		// Enable built-in geolocation
		settings.setGeolocationEnabled(true);

		// Add web view but make it invisible while loading URL
		this.setVisibility(View.INVISIBLE);

		// Clear cancel flag
		this.cancelLoadUrl = false;
		
		Log.d(TAG, "init end");
	}

	public Context getApplicationContext(){
		return getContext().getApplicationContext();
	}
	
    /**
     * Set the WebViewClient.
     * 
     * @param appView
     * @param client
     */
    protected void setWebViewClient(WebView appView, WebViewClient client) {
        this.webViewClient = client;
        appView.setWebViewClient(client);
    }

	@Override
	protected void attachViewToParent(View child, int index,
			ViewGroup.LayoutParams params) {
		if (child.getClass() != EditText.class)
			super.attachViewToParent(child, index, params);
		else {
			super.attachViewToParent(child, index, params);
		}
	}

	@Override
	protected boolean addViewInLayout(View child, int index,
			ViewGroup.LayoutParams params) {
		return super.addViewInLayout(child, index, params);
	}

	@Override
	protected boolean addViewInLayout(View child, int index,
			ViewGroup.LayoutParams params, boolean preventRequestLayout) {
		return super.addViewInLayout(child, index, params);
	}

	/**
	 * Load PhoneGap configuration from res/xml/phonegap.xml. Approved list of
	 * URLs that can be loaded into DroidGap <access
	 * origin="http://server regexp" subdomains="true" /> Log level: ERROR,
	 * WARN, INFO, DEBUG, VERBOSE (default=ERROR) <log level="DEBUG" />
	 */
	private void loadConfiguration() {
		Log.d(TAG, "loadConfiguration");
		int id = getResources().getIdentifier("phonegap", "xml",
				getApplicationContext().getPackageName());
		if (id == 0) {
			LOG.i("PhoneGapLog", "phonegap.xml missing. Ignoring...");
			return;
		}
		XmlResourceParser xml = getResources().getXml(id);
		int eventType = -1;
		while (eventType != XmlResourceParser.END_DOCUMENT) {
			if (eventType == XmlResourceParser.START_TAG) {
				String strNode = xml.getName();
				if (strNode.equals("access")) {
					String origin = xml.getAttributeValue(null, "origin");
					String subdomains = xml.getAttributeValue(null,
							"subdomains");
					if (origin != null) {
						this.addWhiteListEntry(
								origin,
								(subdomains != null)
										&& (subdomains
												.compareToIgnoreCase("true") == 0));
					}
				} else if (strNode.equals("log")) {
					String level = xml.getAttributeValue(null, "level");
					LOG.i("PhoneGapLog", "Found log level %s", level);
					if (level != null) {
						LOG.setLogLevel(level);
					}
				} else if (strNode.equals("render")) {
					String enabled = xml.getAttributeValue(null, "enabled");
					if (enabled != null) {
						this.classicRender = enabled.equals("true");
					}
				} else if (strNode.equals("preference")) {
					String name = xml.getAttributeValue(null, "name");
					String value = xml.getAttributeValue(null, "value");
					String readonlyString = xml.getAttributeValue(null,
							"readonly");

					boolean readonly = (readonlyString != null && readonlyString
							.equals("true"));

					LOG.i("PhoneGapLog", "Found preference for %s", name);

					preferences.add(new PreferenceNode(name, value, readonly));
				}
			}
			try {
				eventType = xml.next();
			} catch (XmlPullParserException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		Log.d(TAG, "loadConfiguration end");
	}

	/**
	 * Add entry to approved list of URLs (whitelist)
	 * 
	 * @param origin
	 *            URL regular expression to allow
	 * @param subdomains
	 *            T=include all subdomains under origin
	 */
	private void addWhiteListEntry(String origin, boolean subdomains) {
		try {
			// Unlimited access to network resources
			if (origin.compareTo("*") == 0) {
				LOG.d(TAG, "Unlimited access to network resources");
				whiteList.add(Pattern.compile("*"));
			} else { // specific access
				// check if subdomains should be included
				// TODO: we should not add more domains if * has already been
				// added
				if (subdomains) {
					// XXX making it stupid friendly for people who forget to
					// include protocol/SSL
					if (origin.startsWith("http")) {
						whiteList.add(Pattern.compile(origin.replaceFirst(
								"https{0,1}://", "^https{0,1}://.*")));
					} else {
						whiteList.add(Pattern.compile("^https{0,1}://.*"
								+ origin));
					}
					LOG.d(TAG, "Origin to allow with subdomains: %s", origin);
				} else {
					// XXX making it stupid friendly for people who forget to
					// include protocol/SSL
					if (origin.startsWith("http")) {
						whiteList.add(Pattern.compile(origin.replaceFirst(
								"https{0,1}://", "^https{0,1}://")));
					} else {
						whiteList.add(Pattern
								.compile("^https{0,1}://" + origin));
					}
					LOG.d(TAG, "Origin to allow: %s", origin);
				}
			}
		} catch (Exception e) {
			LOG.d(TAG, "Failed to add origin %s", origin);
		}
	}
	
    /**
     * Report an error to the host application. These errors are unrecoverable (i.e. the main resource is unavailable). 
     * The errorCode parameter corresponds to one of the ERROR_* constants.
     *
     * @param errorCode    The error code corresponding to an ERROR_* value.
     * @param description  A String describing the error.
     * @param failingUrl   The url that failed to load. 
     */
    public void onReceivedError(final int errorCode, final String description, final String failingUrl) {
        final GapView me = this;

        me.post(new Runnable() {
            public void run() {
                me.setVisibility(View.GONE);
                me.displayError("Application Error", description + " ("+failingUrl+")", "OK", true);
            }
        });
    }

    /**
     * Display an error dialog and optionally exit application.
     * 
     * @param title
     * @param message
     * @param button
     * @param exit
     */
    public void displayError(final String title, final String message, final String button, final boolean exit) {
        final GapView me = this;
        me.post(new Runnable() {
            public void run() {
                AlertDialog.Builder dlg = new AlertDialog.Builder(me.getApplicationContext());
                dlg.setMessage(message);
                dlg.setTitle(title);
                dlg.setCancelable(false);
                dlg.setPositiveButton(button,
                        new AlertDialog.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (exit) {
                            me.endActivity();
                        }
                    }
                });
                dlg.create();
                dlg.show();
            }
        });
    }
    
    /**
	 * Set the chrome handler.
	 */
	public class GapClient extends WebChromeClient {

		private String TAG = "PhoneGapLog";
		private long MAX_QUOTA = 100 * 1024 * 1024;
		private GapView ctx;

		/**
		 * Constructor.
		 * 
		 * @param ctx
		 */
		public GapClient(GapView ctx) {
			this.ctx = ctx;
		}

		/**
		 * Tell the client to display a javascript alert dialog.
		 * 
		 * @param view
		 * @param url
		 * @param message
		 * @param result
		 */
		@Override
		public boolean onJsAlert(WebView view, String url, String message,
				final JsResult result) {
			AlertDialog.Builder dlg = new AlertDialog.Builder(this.ctx.getApplicationContext());
			dlg.setMessage(message);
			dlg.setTitle("Alert");
			// Don't let alerts break the back button
			dlg.setCancelable(true);
			dlg.setPositiveButton(android.R.string.ok,
					new AlertDialog.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							result.confirm();
						}
					});
			dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
				public void onCancel(DialogInterface dialog) {
					result.confirm();
				}
			});
			dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
				// DO NOTHING
				public boolean onKey(DialogInterface dialog, int keyCode,
						KeyEvent event) {
					if (keyCode == KeyEvent.KEYCODE_BACK) {
						result.confirm();
						return false;
					} else
						return true;
				}
			});
			dlg.create();
			dlg.show();
			return true;
		}

		/**
		 * Tell the client to display a confirm dialog to the user.
		 * 
		 * @param view
		 * @param url
		 * @param message
		 * @param result
		 */
		@Override
		public boolean onJsConfirm(WebView view, String url, String message,
				final JsResult result) {
			AlertDialog.Builder dlg = new AlertDialog.Builder(this.ctx.getApplicationContext());
			dlg.setMessage(message);
			dlg.setTitle("Confirm");
			dlg.setCancelable(true);
			dlg.setPositiveButton(android.R.string.ok,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							result.confirm();
						}
					});
			dlg.setNegativeButton(android.R.string.cancel,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							result.cancel();
						}
					});
			dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
				public void onCancel(DialogInterface dialog) {
					result.cancel();
				}
			});
			dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
				// DO NOTHING
				public boolean onKey(DialogInterface dialog, int keyCode,
						KeyEvent event) {
					if (keyCode == KeyEvent.KEYCODE_BACK) {
						result.cancel();
						return false;
					} else
						return true;
				}
			});
			dlg.create();
			dlg.show();
			return true;
		}

		/**
		 * Tell the client to display a prompt dialog to the user. If the client
		 * returns true, WebView will assume that the client will handle the
		 * prompt dialog and call the appropriate JsPromptResult method.
		 * 
		 * Since we are hacking prompts for our own purposes, we should not be
		 * using them for this purpose, perhaps we should hack console.log to do
		 * this instead!
		 * 
		 * @param view
		 * @param url
		 * @param message
		 * @param defaultValue
		 * @param result
		 */
		@Override
		public boolean onJsPrompt(WebView view, String url, String message,
				String defaultValue, JsPromptResult result) {

			// Security check to make sure any requests are coming from the page
			// initially
			// loaded in webview and not another loaded in an iframe.
			boolean reqOk = false;
			if (url.startsWith("file://") || url.indexOf(this.ctx.baseUrl) == 0
					|| isUrlWhiteListed(url)) {
				reqOk = true;
			}

			// Calling PluginManager.exec() to call a native service using
			// prompt(this.stringify(args), "gap:"+this.stringify([service,
			// action, callbackId, true]));
			if (reqOk && defaultValue != null && defaultValue.length() > 3
					&& defaultValue.substring(0, 4).equals("gap:")) {
				JSONArray array;
				try {
					array = new JSONArray(defaultValue.substring(4));
					String service = array.getString(0);
					String action = array.getString(1);
					String callbackId = array.getString(2);
					boolean async = array.getBoolean(3);
					String r = pluginManager.exec(service, action, callbackId,
							message, async);
					result.confirm(r);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}

			// Polling for JavaScript messages
			else if (reqOk && defaultValue != null
					&& defaultValue.equals("gap_poll:")) {
				String r = callbackServer.getJavascript();
				result.confirm(r);
			}

			// Calling into CallbackServer
			else if (reqOk && defaultValue != null
					&& defaultValue.equals("gap_callbackServer:")) {
				String r = "";
				if (message.equals("usePolling")) {
					r = "" + callbackServer.usePolling();
				} else if (message.equals("restartServer")) {
					callbackServer.restartServer();
				} else if (message.equals("getPort")) {
					r = Integer.toString(callbackServer.getPort());
				} else if (message.equals("getToken")) {
					r = callbackServer.getToken();
				}
				result.confirm(r);
			}

			// PhoneGap JS has initialized, so show webview
			// (This solves white flash seen when rendering HTML)
			else if (reqOk && defaultValue != null
					&& defaultValue.equals("gap_init:")) {
				setVisibility(View.VISIBLE);
				ctx.spinnerStop();
				result.confirm("OK");
			}

			// Show dialog
			else {
				final JsPromptResult res = result;
				AlertDialog.Builder dlg = new AlertDialog.Builder(this.ctx.getApplicationContext());
				dlg.setMessage(message);
				final EditText input = new EditText(this.ctx.getApplicationContext());
				if (defaultValue != null) {
					input.setText(defaultValue);
				}
				dlg.setView(input);
				dlg.setCancelable(false);
				dlg.setPositiveButton(android.R.string.ok,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {
								String usertext = input.getText().toString();
								res.confirm(usertext);
							}
						});
				dlg.setNegativeButton(android.R.string.cancel,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {
								res.cancel();
							}
						});
				dlg.create();
				dlg.show();
			}
			return true;
		}

		/**
		 * Handle database quota exceeded notification.
		 * 
		 * @param url
		 * @param databaseIdentifier
		 * @param currentQuota
		 * @param estimatedSize
		 * @param totalUsedQuota
		 * @param quotaUpdater
		 */
		@Override
		public void onExceededDatabaseQuota(String url,
				String databaseIdentifier, long currentQuota,
				long estimatedSize, long totalUsedQuota,
				WebStorage.QuotaUpdater quotaUpdater) {
			LOG.d(TAG,
					"DroidGap:  onExceededDatabaseQuota estimatedSize: %d  currentQuota: %d  totalUsedQuota: %d",
					estimatedSize, currentQuota, totalUsedQuota);

			if (estimatedSize < MAX_QUOTA) {
				// increase for 1Mb
				long newQuota = estimatedSize;
				LOG.d(TAG, "calling quotaUpdater.updateQuota newQuota: %d",
						newQuota);
				quotaUpdater.updateQuota(newQuota);
			} else {
				// Set the quota to whatever it is and force an error
				// TODO: get docs on how to handle this properly
				quotaUpdater.updateQuota(currentQuota);
			}
		}

		// console.log in api level 7:
		// http://developer.android.com/guide/developing/debug-tasks.html
		@Override
		public void onConsoleMessage(String message, int lineNumber,
				String sourceID) {
			LOG.d(TAG, "%s: Line %d : %s", sourceID, lineNumber, message);
			super.onConsoleMessage(message, lineNumber, sourceID);
		}

		@Override
		public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
			LOG.d(TAG, consoleMessage.message());
			return super.onConsoleMessage(consoleMessage);
		}

		@Override
		/**
		 * Instructs the client to show a prompt to ask the user to set the Geolocation permission state for the specified origin. 
		 * 
		 * @param origin
		 * @param callback
		 */
		public void onGeolocationPermissionsShowPrompt(String origin,
				Callback callback) {
			super.onGeolocationPermissionsShowPrompt(origin, callback);
			callback.invoke(origin, true, false);
		}

	}
	
    /**
     * Determine if URL is in approved list of URLs to load.
     * 
     * @param url
     * @return
     */
    public boolean isUrlWhiteListed(String url) {

        // Check to see if we have matched url previously
        if (whiteListCache.get(url) != null) {
            return true;
        }

        // Look for match in white list
        Iterator<Pattern> pit = whiteList.iterator();
        while (pit.hasNext()) {
            Pattern p = pit.next();
            Matcher m = p.matcher(url);

            // If match found, then cache it to speed up subsequent comparisons
            if (m.find()) {
                whiteListCache.put(url, true);
                return true;
            }
        }
        return false;
    }
    
    /**
     * Send a message to all plugins. 
     * 
     * @param id            The message id
     * @param data          The message data
     */
    public void postMessage(String id, Object data) {
        
        // Forward to plugins
        this.pluginManager.postMessage(id, data);
    }
    
    /**
     * Send JavaScript statement back to JavaScript.
     * (This is a convenience method)
     * 
     * @param message
     */
    public void sendJavascript(String statement) {
        this.callbackServer.sendJavascript(statement);
    }

    /**
     * Load the specified URL in the PhoneGap webview or a new browser instance.
     * 
     * NOTE: If openExternal is false, only URLs listed in whitelist can be loaded.
     *
     * @param url           The url to load.
     * @param openExternal  Load url in browser instead of PhoneGap webview.
     * @param clearHistory  Clear the history stack, so new page becomes top of history
     * @param params        DroidGap parameters for new app
     */
    public void showWebPage(String url, boolean openExternal, boolean clearHistory, HashMap<String, Object> params) { //throws android.content.ActivityNotFoundException {
        LOG.d(TAG, "showWebPage(%s, %b, %b, HashMap", url, openExternal, clearHistory);
        
        // If clearing history
        if (clearHistory) {
            this.clearHistory();
        }
        
        // If loading into our webview
        if (!openExternal) {
            
            // Make sure url is in whitelist
            if (url.startsWith("file://") || url.indexOf(this.baseUrl) == 0 || isUrlWhiteListed(url)) {
                // TODO: What about params?
                
                // Clear out current url from history, since it will be replacing it
                if (clearHistory) {
                    this.urls.clear();
                }
                
                // Load new URL
                this.loadUrl(url);
            }
            // Load in default viewer if not
            else {
                LOG.w(TAG, "showWebPage: Cannot load URL into webview since it is not in white list.  Loading into browser instead. (URL="+url+")");
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    this.startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    LOG.e(TAG, "Error loading url "+url, e);
                }
            }
        }
        
        // Load in default view intent
        else {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                this.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                LOG.e(TAG, "Error loading url "+url, e);
            }
        }
    }
    
    /**
     * Show the spinner.  Must be called from the UI thread.
     * 
     * @param title         Title of the dialog
     * @param message       The message of the dialog
     */
    public void spinnerStart(final String title, final String message) {
        if (this.spinnerDialog != null) {
            this.spinnerDialog.dismiss();
            this.spinnerDialog = null;
        }
        final GapView me = this;
        this.spinnerDialog = ProgressDialog.show(GapView.this.getApplicationContext(), title , message, true, true, 
                new DialogInterface.OnCancelListener() { 
            public void onCancel(DialogInterface dialog) {
                me.spinnerDialog = null;
            }
        });
    }

    /**
     * Stop spinner.
     */
    public void spinnerStop() {
        if (this.spinnerDialog != null) {
            this.spinnerDialog.dismiss();
            this.spinnerDialog = null;
        }
    }

    /**
     * End this activity by calling finish for activity
     */
    public void endActivity() {
        if(mActivity!=null) mActivity.finish();
    }
    
    public final Cursor managedQuery (Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    	if(mActivity!=null) return mActivity.managedQuery(uri, projection, selection, selectionArgs, sortOrder);
    	return null;
    }
    
    public void startActivity(Intent intent){
    	if(mActivity!=null) mActivity.startActivity(intent);
    }
    
    /**
     * Any calls to Activity.startActivityForResult must use method below, so 
     * the result can be routed to them correctly.  
     * 
     * This is done to eliminate the need to modify DroidGap.java to receive activity results.
     * 
     * @param intent            The intent to start
     * @param requestCode       Identifies who to send the result to
     * 
     * @throws RuntimeException
     */
    public void startActivityForResult(Intent intent, int requestCode) throws RuntimeException {
        LOG.d(TAG, "DroidGap.startActivityForResult(intent,%d)", requestCode);
        if(mActivity!=null) mActivity.startActivityForResult(intent, requestCode);
    }

    /**
     * Launch an activity for which you would like a result when it finished. When this activity exits, 
     * your onActivityResult() method will be called.
     *  
     * @param command           The command object
     * @param intent            The intent to start
     * @param requestCode       The request code that is passed to callback to identify the activity
     */
    public void startActivityForResult(IPlugin command, Intent intent, int requestCode) {
        this.activityResultCallback = command;
        
        // Start activity
        if(mActivity!=null) mActivity.startActivityForResult(intent, requestCode);
    }
    
    public void setActivityResultCallback(IPlugin plugin) {
        this.activityResultCallback = plugin;
    }
    
    public void activityResult(int requestCode, int resultCode, Intent intent) {
         IPlugin callback = this.activityResultCallback;
         if (callback != null) {
             callback.onActivityResult(requestCode, resultCode, intent);
         }        
     }

    public boolean keyDown(int keyCode, KeyEvent event) {
        // If back key
        if (keyCode == KeyEvent.KEYCODE_BACK) {

            // If back key is bound, then send event to JavaScript
            if (this.bound) {
                this.loadUrl("javascript:PhoneGap.fireDocumentEvent('backbutton');");
                return true;
            }

            // If not bound
            else {

                // Go to previous page in webview if it is possible to go back
                if (this.backHistory()) {
                    return true;
                }

                // If not, then invoke behavior of super class
                else {
                    return super.onKeyDown(keyCode, event);
                }
            }
        }

        // If menu key
        else if (keyCode == KeyEvent.KEYCODE_MENU) {
            this.loadUrl("javascript:PhoneGap.fireDocumentEvent('menubutton');");
            return super.onKeyDown(keyCode, event);
        }

        // If search key
        else if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            this.loadUrl("javascript:PhoneGap.fireDocumentEvent('searchbutton');");
            return true;
        }

        return false;
    }
    
    public void newIntent(Intent intent){
        //Forward to plugins
        this.pluginManager.onNewIntent(intent);
    }
    
    public void pause(){
        // Send pause event to JavaScript
        this.loadUrl("javascript:try{PhoneGap.fireDocumentEvent('pause');}catch(e){};");

        // Forward to plugins
        this.pluginManager.onPause(false);

        // Pause JavaScript timers (including setInterval)
        this.pauseTimers();
    }
    
    public void resume(){
        // Send resume event to JavaScript
        this.loadUrl("javascript:try{PhoneGap.fireDocumentEvent('resume');}catch(e){};");

        // Forward to plugins
        Log.i(TAG, "pluginManager="+pluginManager);
        this.pluginManager.onResume(false);

        // Resume JavaScript timers (including setInterval)
        this.resumeTimers();
    }
    
    public void destroy(){
        // Send destroy event to JavaScript
        this.loadUrl("javascript:try{PhoneGap.onDestroy.fire();}catch(e){};");

        // Load blank page so that JavaScript onunload is called
        this.loadUrl("about:blank");

        // Forward to plugins
        this.pluginManager.onDestroy();
    }
    
    public Activity getActivity(){
    	return mActivity;
    }
    
    public void setActivity(Activity activity){
    	this.mActivity = activity;
    	
    }
}
