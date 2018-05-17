package com.squareup.whorlwind.sample;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ListView;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.rxbinding2.view.RxView;
import com.jakewharton.rxbinding2.widget.RxTextView;
import com.mattprecious.swirl.SwirlView;
import com.squareup.whorlwind.ReadResult;
import com.squareup.whorlwind.Whorlwind;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import okio.ByteString;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class SampleActivity extends Activity {
  private final PublishSubject<String> readSubject = PublishSubject.create();
  private final SampleStorage storage = new SampleStorage();
  @BindView(R.id.content) View contentView;
  @BindView(R.id.swirl) SwirlView swirlView;
  @BindView(R.id.message) TextSwitcher messageView;
  @BindView(R.id.write) View writeView;
  @BindView(R.id.key) EditText keyView;
  @BindView(R.id.value) EditText valueView;
  @BindView(R.id.list) ListView listView;
  private Whorlwind whorlwind;
  private CompositeDisposable disposables;
  private SampleAdapter adapter;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity);
    ButterKnife.bind(this);

    whorlwind = Whorlwind.create(this, storage, "sample");

    // Set up the TextSwitcher.
    messageView.setFactory(() -> {
      TextView textView = new TextView(this);
      textView.setGravity(Gravity.CENTER);

      LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
      params.gravity = Gravity.CENTER;
      textView.setLayoutParams(params);

      return textView;
    });

    adapter = new SampleAdapter(this, readSubject::onNext);
    listView.setAdapter(adapter);

    // Seed the storage with a sample value.
    if (whorlwind.canStoreSecurely()) {
      Observable.just("Hello world!")
          .observeOn(Schedulers.io())
          .flatMapCompletable(value -> whorlwind.write("sample", ByteString.encodeUtf8(value)))
          .subscribe();
    }
  }

  @Override protected void onResume() {
    super.onResume();
    disposables = new CompositeDisposable();

    if (!whorlwind.canStoreSecurely()) {
      messageView.setText("Cannot store securely. If you have a fingerprint reader, make sure " //
          + "you have a fingerprint enrolled.");
      keyView.setEnabled(false);
      valueView.setEnabled(false);
      writeView.setEnabled(false);
      return;
    }

    messageView.setText(null);

    // Write a new value to secure storage.
    disposables.add(RxView.clicks(writeView).map(click -> true) //
        .map(ignored -> //
            Pair.create(keyView.getText().toString(), valueView.getText().toString())) //
        .doOnNext(ignored -> {
          swirlView.setState(SwirlView.State.OFF);
          messageView.setText(null);
          keyView.setText(null);
          valueView.setText(null);
          contentView.requestFocus();
          hideKeyboard();
        }) //
        .observeOn(Schedulers.io()) //
        .flatMapCompletable(data -> whorlwind.write(data.first, ByteString.encodeUtf8(data.second)))
        .subscribe());

    // Read a value from secure storage for a provided key.
    ConnectableObservable<ReadResult> readResult = readSubject //
        .switchMap(key -> whorlwind.read(key) //
            .subscribeOn(Schedulers.io())) //
        .publish();

    // If a value is not found in storage, the first item emitted will be a READY result with a null
    // value. This shouldn't be possible in this sample.
    disposables.add(readResult.take(1) //
        .filter(result -> result.readState == ReadResult.ReadState.READY) //
        .observeOn(AndroidSchedulers.mainThread()) //
        .subscribe(ignored -> {
          Toast.makeText(this, "How did you do that!?", Toast.LENGTH_SHORT).show();
        }));

    // Toast the decrypted value. See above for an explanation of the skip(1).
    disposables.add(readResult //
        .skip(1) //
        .filter(result -> result.readState == ReadResult.ReadState.READY) //
        .map(result -> result.value.utf8()) //
        .observeOn(AndroidSchedulers.mainThread()) //
        .subscribe(value -> Toast.makeText(this, value, Toast.LENGTH_SHORT).show()));

    // Update the fingerprint icon.
    disposables.add(readResult //
        .map(result -> {
          switch (result.readState) {
            case NEEDS_AUTH:
              return SwirlView.State.ON;
            case UNRECOVERABLE_ERROR:
            case AUTHORIZATION_ERROR:
            case RECOVERABLE_ERROR:
              return SwirlView.State.ERROR;
            case READY:
              return SwirlView.State.OFF;
            default:
              throw new IllegalArgumentException("Unknown state: " + result.readState);
          }
        }) //
        .observeOn(AndroidSchedulers.mainThread()) //
        .subscribe(swirlView::setState));

    // Show error messages. The read result will usually contain help/error messages from Android
    // that should be shown. If you wish to customize these messages, you can check the code in the
    // result.
    disposables.add(readResult //
        .map(result -> {
          if (result.message != null) {
            return result.message;
          }

          // Fall back to default messages.
          switch (result.readState) {
            case NEEDS_AUTH:
              return "Please verify your fingerprint";
            case AUTHORIZATION_ERROR:
              return "Not recognized";
            case RECOVERABLE_ERROR:
              return "Please try again";
            case UNRECOVERABLE_ERROR:
              return "Something went wrong";
            case READY:
              return "";
            default:
              throw new IllegalArgumentException("Unknown state: " + result.readState);
          }
        }) //
        .observeOn(AndroidSchedulers.mainThread()) //
        .subscribe(messageView::setText));

    // Automatically clear the error icon after 1.3 seconds if the error is recoverable.
    disposables.add(readResult //
        .switchMap(result -> Observable.just(result.readState)
            .filter(state -> state == ReadResult.ReadState.AUTHORIZATION_ERROR
                || state == ReadResult.ReadState.RECOVERABLE_ERROR)
            .delay(1300, MILLISECONDS)) //
        .map(ignored -> SwirlView.State.ON) //
        .subscribeOn(Schedulers.io()) //
        .observeOn(AndroidSchedulers.mainThread()) //
        .subscribe(swirlView::setState));

    disposables.add(readResult.connect());

    // Only allow writing non-null keys and values.
    disposables.add(
        Observable.combineLatest(RxTextView.textChanges(keyView),
                RxTextView.textChanges(valueView),
            (key, value) -> !TextUtils.isEmpty(key) && !TextUtils.isEmpty(value)) //
            .subscribe(writeView::setEnabled));

    // Update the list with values from our storage.
    disposables.add(storage.entries() //
        .observeOn(AndroidSchedulers.mainThread()) //
        .subscribe(adapter));
  }

  @Override protected void onPause() {
    super.onPause();
    disposables.dispose();
  }

  private void hideKeyboard() {
    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)) //
        .hideSoftInputFromWindow(swirlView.getWindowToken(), 0);
  }
}
