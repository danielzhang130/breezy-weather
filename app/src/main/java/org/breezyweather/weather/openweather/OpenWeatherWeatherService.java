package org.breezyweather.weather.openweather;

import android.content.Context;

import android.text.TextUtils;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import org.breezyweather.common.basic.models.Location;
import org.breezyweather.main.utils.RequestErrorType;
import org.breezyweather.weather.WeatherService;
import org.breezyweather.weather.openmeteo.OpenMeteoGeocodingApi;
import org.breezyweather.common.basic.models.options.provider.WeatherSource;
import org.breezyweather.common.rxjava.BaseObserver;
import org.breezyweather.common.rxjava.ObserverContainer;
import org.breezyweather.common.rxjava.SchedulerTransformer;
import org.breezyweather.settings.SettingsManager;
import org.breezyweather.weather.openmeteo.json.OpenMeteoLocationResult;
import org.breezyweather.weather.openmeteo.json.OpenMeteoLocationResults;
import org.breezyweather.weather.openweather.json.OpenWeatherAirPollutionResult;
import org.breezyweather.weather.openweather.json.OpenWeatherOneCallResult;
import org.breezyweather.weather.openmeteo.OpenMeteoResultConverterKt;

/**
 * OpenWeather weather service.
 */
public class OpenWeatherWeatherService extends WeatherService {

    private final OpenWeatherApi mApi;
    private final OpenMeteoGeocodingApi mGeocodingApi;
    private final CompositeDisposable mCompositeDisposable;

    @Inject
    public OpenWeatherWeatherService(OpenWeatherApi api, OpenMeteoGeocodingApi geocodingApi, CompositeDisposable disposable) {
        mApi = api;
        mGeocodingApi = geocodingApi;
        mCompositeDisposable = disposable;
    }

    protected String getApiKey(Context context) {
        return SettingsManager.getInstance(context).getProviderOpenWeatherKey();
    }

    @Override
    public Boolean isConfigured(Context context) {
        return !TextUtils.isEmpty(getApiKey(context));
    }

    @Override
    public void requestWeather(Context context, Location location, @NonNull RequestWeatherCallback callback) {
        if (!isConfigured(context)) {
            callback.requestWeatherFailed(location, RequestErrorType.API_KEY_REQUIRED_MISSING);
            return;
        }

        String apiKey = getApiKey(context);
        String languageCode = SettingsManager.getInstance(context).getLanguage().getCode();

        Observable<OpenWeatherOneCallResult> oneCall = mApi.getOneCall(
                SettingsManager.getInstance(context).getCustomOpenWeatherOneCallVersion().getId(),
                apiKey,
                location.getLatitude(),
                location.getLongitude(),
                "metric",
                languageCode
        );

        Observable<OpenWeatherAirPollutionResult> airPollution = mApi.getAirPollution(
                apiKey, location.getLatitude(), location.getLongitude()
        ).onErrorResumeNext(error ->
                Observable.create(emitter -> emitter.onNext(new OpenWeatherAirPollutionResult(null)))
        );

        Observable.zip(oneCall, airPollution,
                (openWeatherOneCallResult, openWeatherAirPollutionResult) -> OpenWeatherResultConverterKt.convert(
                        context,
                        location,
                        openWeatherOneCallResult,
                        openWeatherAirPollutionResult
                )
        ).compose(SchedulerTransformer.create())
                .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<WeatherResultWrapper>() {
                    @Override
                    public void onSucceed(WeatherResultWrapper wrapper) {
                        if (wrapper.getResult() != null) {
                            callback.requestWeatherSuccess(
                                    Location.copy(location, wrapper.getResult())
                            );
                        } else {
                            onFailed();
                        }
                    }

                    @Override
                    public void onFailed() {
                        if (this.isApiLimitReached()) {
                            callback.requestWeatherFailed(location, RequestErrorType.API_LIMIT_REACHED);
                        } else if (this.isApiUnauthorized()) {
                            callback.requestWeatherFailed(location, RequestErrorType.API_UNAUTHORIZED);
                        } else {
                            callback.requestWeatherFailed(location, RequestErrorType.WEATHER_REQ_FAILED);
                        }
                    }
                }));
    }

    @Override
    @NonNull
    public List<Location> requestLocation(Context context, String query) {
        String languageCode = SettingsManager.getInstance(context).getLanguage().getCode();
        OpenMeteoLocationResults results = null;
        try {
            results = mGeocodingApi.callWeatherLocation(
                    query, 20, languageCode).execute().body();
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Location> locationList = new ArrayList<>();
        if (results != null && results.getResults() != null && results.getResults().size() != 0) {
            for (OpenMeteoLocationResult r : results.getResults()) {
                locationList.add(OpenMeteoResultConverterKt.convert(null, r, WeatherSource.OPEN_WEATHER));
            }
        }
        return locationList;
    }

    // Reverse geocoding
    @Override
    public void requestLocation(Context context, Location location,
                                @NonNull RequestLocationCallback callback) {
        // Currently there is no reverse geocoding, so we just return the same location
        // TimeZone is initialized with the TimeZone from the phone (which is probably the same as the current position)
        // Hopefully, one day we will have a reverse geocoding API
        List<Location> locationList = new ArrayList<>();
        locationList.add(location);
        callback.requestLocationSuccess(
                location.getLatitude() + "," + location.getLongitude(),
                locationList
        );
    }

    public void requestLocation(Context context, String query,
                                @NonNull RequestLocationCallback callback) {
        String languageCode = SettingsManager.getInstance(context).getLanguage().getCode();
        mGeocodingApi.getWeatherLocation(query, 20, languageCode)
                .compose(SchedulerTransformer.create())
                .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<OpenMeteoLocationResults>() {
                    @Override
                    public void onSucceed(OpenMeteoLocationResults openMeteoLocationResults) {
                        if (openMeteoLocationResults.getResults() != null && openMeteoLocationResults.getResults().size() != 0) {
                            List<Location> locationList = new ArrayList<>();
                            for (OpenMeteoLocationResult r : openMeteoLocationResults.getResults()) {
                                locationList.add(OpenMeteoResultConverterKt.convert(null, r, WeatherSource.OPEN_WEATHER));
                            }
                            callback.requestLocationSuccess(query, locationList);
                        } else {
                            callback.requestLocationFailed(query, RequestErrorType.LOCATION_FAILED);
                        }

                    }

                    @Override
                    public void onFailed() {
                        callback.requestLocationFailed(query, RequestErrorType.LOCATION_FAILED);
                    }
                }));
    }

    @Override
    public void cancel() {
        mCompositeDisposable.clear();
    }
}