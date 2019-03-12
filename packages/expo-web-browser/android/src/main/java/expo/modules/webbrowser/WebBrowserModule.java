package expo.modules.webbrowser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.support.customtabs.CustomTabsIntent;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import expo.core.ExportedModule;
import expo.core.ModuleRegistry;
import expo.core.Promise;
import expo.core.arguments.ReadableArguments;
import expo.core.interfaces.ActivityEventListener;
import expo.core.interfaces.ExpoMethod;
import expo.core.interfaces.ModuleRegistryConsumer;
import expo.core.interfaces.services.UIManager;
import expo.errors.CurrentActivityNotFoundException;
import expo.modules.webbrowser.error.PackageManagerNotFoundException;

public class WebBrowserModule extends ExportedModule implements ModuleRegistryConsumer, ActivityEventListener {
  private final static String ERROR_CODE = "EXWebBrowser";
  private static final String TAG = "ExpoWebBrowser";
  private Promise mOpenBrowserPromise;
  private static int OPEN_BROWSER_REQUEST_CODE = 873;

  private CustomTabsActivitiesHelper mResolver;
  private CustomTabsConnectionHelper mConnectionHelper;

  public WebBrowserModule(Context context) {
    super(context);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public void setModuleRegistry(ModuleRegistry moduleRegistry) {
    mResolver = new CustomTabsActivitiesHelper(moduleRegistry);
    mConnectionHelper = new CustomTabsConnectionHelper(getContext());
    if (moduleRegistry != null) {
      moduleRegistry.getModule(UIManager.class).registerActivityEventListener(this);
    }
  }

  @ExpoMethod
  public void warmUp(final String packageName, final Promise promise) {
    mConnectionHelper.warmUp(packageName);
    Bundle result = new Bundle();
    result.putString("type", "warming");
    result.putString("package", packageName);
    promise.resolve(result);
  }

  @ExpoMethod
  public void coolDown(final String packageName, final Promise promise) {
    if (mConnectionHelper.coolDown(packageName)) {
      Bundle result = new Bundle();
      result.putString("type", "cooled");
      result.putString("package", packageName);
      promise.resolve(result);
    } else {
      Bundle result = new Bundle();
      result.putString("type", "Nothing to cool down");
      result.putString("package", packageName);
      promise.resolve(result);
    }
  }

//
//  @ExpoMethod
//  public void mayInitWithUrl() {
//  }

  @ExpoMethod
  public void getCustomTabsSupportingBrowsersAsync(final Promise promise) {
    try {
      ArrayList<String> activities = mResolver.getCustomTabsResolvingActivities();
      ArrayList<String> services = mResolver.getCustomTabsResolvingServices();
      String preferredPackage = mResolver.getPreferredCustomTabsResolvingActivity(activities);
      String defaultPackage = mResolver.getDefaultCustomTabsResolvingActivity();

      String defaultCustomTabsPackage = null;
      if (activities.contains(defaultPackage)) { // It might happen, that default activity does not support Chrome Tabs. Then it will be ResolvingActivity and we don't want to return it as a result.
        defaultCustomTabsPackage = defaultPackage;
      }

      Bundle result = new Bundle();
      result.putStringArrayList("views", activities);
      result.putStringArrayList("services", services);
      result.putString("preferred", preferredPackage);
      result.putString("default", defaultCustomTabsPackage);

      promise.resolve(result);
    } catch (CurrentActivityNotFoundException | PackageManagerNotFoundException ex) {
      promise.reject(ERROR_CODE, "Unable to determine list of supporting applications");
    }
  }

  @ExpoMethod
  public void openBrowserAsync(final String url, ReadableArguments arguments, final Promise promise) {
    if (mOpenBrowserPromise != null) {
      Bundle result = new Bundle();
      result.putString("type", "cancel");
      mOpenBrowserPromise.resolve(result);
      return;
    }
    mOpenBrowserPromise = promise;

    Intent intent = createCustomTabsIntent(arguments);
    intent.setData(Uri.parse(url));

    try {
      List<ResolveInfo> activities = mResolver.getResolvingActivities(intent);
      if (activities.size() > 0) {
        mResolver.startCustomTabs(intent, OPEN_BROWSER_REQUEST_CODE);
      } else {
        promise.reject(ERROR_CODE, "No matching activity!");
      }
    } catch (CurrentActivityNotFoundException ex) {
      promise.reject(ERROR_CODE, "No activity");
      mOpenBrowserPromise = null;
    } catch (PackageManagerNotFoundException ex) {
      promise.reject(ERROR_CODE, "No package manager");
      mOpenBrowserPromise = null;
    }

  }

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
    if (requestCode == OPEN_BROWSER_REQUEST_CODE && mOpenBrowserPromise != null) {
      if (resultCode == CustomTabsManagerActivity.DISMISSED_CODE) {
        Bundle result = new Bundle();
        result.putString("type", "cancel");
        mOpenBrowserPromise.resolve(result);
      }
      mOpenBrowserPromise = null;
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    // do nothing
  }

  private Intent createCustomTabsIntent(ReadableArguments arguments) {
    CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
    String color = arguments.getString("toolbarColor");
    String packageName = arguments.getString("package");
    try {
      if (!TextUtils.isEmpty(color)) {
        int intColor = Color.parseColor(color);
        builder.setToolbarColor(intColor);
      }
    } catch (IllegalArgumentException ignored) {
    }

    builder.setShowTitle(arguments.getBoolean("showTitle", false));

    Intent intent = builder.build().intent;

    // We cannot use builder's method enableUrlBarHiding, because there is no corresponding disable method and some browsers enables it by default.
    intent.putExtra(CustomTabsIntent.EXTRA_ENABLE_URLBAR_HIDING, arguments.getBoolean("enableBarCollapsing", false));
    if (!TextUtils.isEmpty(packageName)) {
      intent.setPackage(packageName);
    }

    return intent;
  }

}
