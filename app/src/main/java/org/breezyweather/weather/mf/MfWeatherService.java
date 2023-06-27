package org.breezyweather.weather.mf;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import org.breezyweather.common.basic.models.Location;
import org.breezyweather.common.basic.models.options.provider.WeatherSource;
import org.breezyweather.main.utils.RequestErrorType;
import org.breezyweather.settings.SettingsManager;
import org.breezyweather.weather.WeatherService;
import org.breezyweather.weather.openmeteo.OpenMeteoGeocodingApi;
import org.breezyweather.BuildConfig;
import org.breezyweather.common.rxjava.BaseObserver;
import org.breezyweather.common.rxjava.ObserverContainer;
import org.breezyweather.common.rxjava.SchedulerTransformer;
import org.breezyweather.common.utils.DisplayUtils;
import org.breezyweather.weather.mf.json.atmoaura.AtmoAuraPointResult;
import org.breezyweather.weather.mf.json.MfCurrentResult;
import org.breezyweather.weather.mf.json.MfEphemerisResult;
import org.breezyweather.weather.mf.json.MfForecastResult;
import org.breezyweather.weather.mf.json.MfRainResult;
import org.breezyweather.weather.mf.json.MfWarningsResult;
import org.breezyweather.weather.openmeteo.json.OpenMeteoLocationResult;
import org.breezyweather.weather.openmeteo.json.OpenMeteoLocationResults;
import org.breezyweather.weather.openmeteo.OpenMeteoResultConverterKt;

/**
 * Mf weather service.
 */

public class MfWeatherService extends WeatherService {

    private final MfWeatherApi mMfApi;
    private final OpenMeteoGeocodingApi mGeocodingApi;
    private final AtmoAuraIqaApi mAtmoAuraApi;
    private final CompositeDisposable mCompositeDisposable;

    @Inject
    public MfWeatherService(MfWeatherApi mfApi, OpenMeteoGeocodingApi geocodingApi, AtmoAuraIqaApi atmoApi,
                            CompositeDisposable disposable) {
        mMfApi = mfApi;
        mGeocodingApi = geocodingApi;
        mAtmoAuraApi = atmoApi;
        mCompositeDisposable = disposable;
    }

    @Override
    public void requestWeather(Context context, Location location, @NonNull RequestWeatherCallback callback) {
        if (!isConfigured(context)) {
            callback.requestWeatherFailed(location, RequestErrorType.API_KEY_REQUIRED_MISSING);
            return;
        }

        String languageCode = SettingsManager.getInstance(context).getLanguage().getCode();
        String token = this.getToken(context);

        Observable<MfCurrentResult> current = mMfApi.getCurrent(
            getUserAgent(), location.getLatitude(), location.getLongitude(), languageCode, "iso", token);

        Observable<MfForecastResult> forecast = mMfApi.getForecast(
            getUserAgent(), location.getLatitude(), location.getLongitude(),"iso", token);

        Observable<MfEphemerisResult> ephemeris = mMfApi.getEphemeris(
            getUserAgent(), location.getLatitude(), location.getLongitude(), "en", "iso", token);
        // English required to convert moon phase

        Observable<MfRainResult> rain = mMfApi.getRain(
            getUserAgent(), location.getLatitude(), location.getLongitude(), languageCode, "iso", token);

        Observable<MfWarningsResult> warnings;
        if (!TextUtils.isEmpty(location.getCountryCode()) && location.getCountryCode().equals("FR")
                && !TextUtils.isEmpty(location.getProvinceCode())) {
            warnings = mMfApi.getWarnings(
                    getUserAgent(), location.getProvinceCode(), "iso", token
            ).onErrorResumeNext(error ->
                    Observable.create(emitter -> emitter.onNext(new MfWarningsResult(null, null, null, null, null, null, null, null)))
            );
        } else {
            warnings = Observable.create(emitter -> emitter.onNext(new MfWarningsResult(null, null, null, null, null, null, null, null)));
        }

        String atmoAuraKey;
        Observable<AtmoAuraPointResult> aqiAtmoAura;
        if (!TextUtils.isEmpty(atmoAuraKey = SettingsManager.getInstance(context).getProviderIqaAtmoAuraKey())
                && !TextUtils.isEmpty(location.getCountryCode()) && location.getCountryCode().equals("FR")
                && !TextUtils.isEmpty(location.getProvinceCode()) &&
                (location.getProvinceCode().equals("01") || location.getProvinceCode().equals("03")
                || location.getProvinceCode().equals("07") || location.getProvinceCode().equals("15")
                || location.getProvinceCode().equals("26") || location.getProvinceCode().equals("38")
                || location.getProvinceCode().equals("42") || location.getProvinceCode().equals("43")
                || location.getProvinceCode().equals("63") || location.getProvinceCode().equals("69")
                || location.getProvinceCode().equals("73") || location.getProvinceCode().equals("74"))
        ) {
            Calendar c = DisplayUtils.toCalendarWithTimeZone(new Date(), location.getTimeZone());
            c.add(Calendar.DATE, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            aqiAtmoAura = mAtmoAuraApi.getPointDetails(
                    atmoAuraKey,
                    location.getLongitude(),
                    location.getLatitude(),
                    // Tomorrow because it gives access to D-1 and D+1
                    DisplayUtils.getFormattedDate(c.getTime(), location.getTimeZone(), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            ).onErrorResumeNext(error ->
                    Observable.create(emitter -> emitter.onNext(new AtmoAuraPointResult(null)))
            );
        } else {
            aqiAtmoAura = Observable.create(emitter -> emitter.onNext(new AtmoAuraPointResult(null)));
        }

        Observable.zip(current, forecast, ephemeris, rain, warnings, aqiAtmoAura,
                (mfCurrentResult, mfForecastResult, mfEphemerisResult, mfRainResult, mfWarningResults, aqiAtmoAuraResult) -> MfResultConverterKt.convert(
                        context,
                        location,
                        mfCurrentResult,
                        mfForecastResult,
                        mfEphemerisResult,
                        mfRainResult,
                        mfWarningResults,
                        aqiAtmoAuraResult
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
        OpenMeteoLocationResults results = null;
        try {
            results = mGeocodingApi.callWeatherLocation(
                    query, 20, "fr").execute().body(); // French mandatory for French department conversion
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Location> locationList = new ArrayList<>();
        if (results != null && results.getResults() != null && results.getResults().size() != 0) {
            for (OpenMeteoLocationResult r : results.getResults()) {
                locationList.add(OpenMeteoResultConverterKt.convert(null, r, WeatherSource.MF));
            }
        }
        return locationList;
    }

    // Reverse geocoding
    @Override
    public void requestLocation(Context context, Location location,
                                @NonNull RequestLocationCallback callback) {
        if (!isConfigured(context)) {
            callback.requestLocationFailed(
                    location.getLatitude() + "," + location.getLongitude(),
                    RequestErrorType.API_KEY_REQUIRED_MISSING
            );
            return;
        }

        mMfApi.getForecast(
            getUserAgent(),
            location.getLatitude(),
            location.getLongitude(),
            "iso",
            getToken(context)
        ).compose(SchedulerTransformer.create())
            .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<MfForecastResult>() {
                @Override
                public void onSucceed(MfForecastResult mfForecastResult) {
                    if (mfForecastResult != null) {
                        List<Location> locationList = new ArrayList<>();
                        Location location = MfResultConverterKt.convert(null, mfForecastResult);
                        if (location != null) {
                            locationList.add(location);
                            callback.requestLocationSuccess(
                                    location.getLatitude() + "," + location.getLongitude(),
                                    locationList
                            );
                        } else {
                            onFailed();
                        }
                    } else {
                        onFailed();
                    }
                }

                @Override
                public void onFailed() {
                    if (this.isApiLimitReached()) {
                        callback.requestLocationFailed(location.getLatitude() + "," + location.getLongitude(), RequestErrorType.API_LIMIT_REACHED);
                    } else if (this.isApiUnauthorized()) {
                        callback.requestLocationFailed(location.getLatitude() + "," + location.getLongitude(), RequestErrorType.API_UNAUTHORIZED);
                    } else {
                        callback.requestLocationFailed(location.getLatitude() + "," + location.getLongitude(), RequestErrorType.LOCATION_FAILED);
                    }
                }
            }));
    }

    public void requestLocation(Context context, String query,
                                @NonNull RequestLocationCallback callback) {
        mGeocodingApi.getWeatherLocation(query, 20, "fr") // French mandatory for French department conversion
                .compose(SchedulerTransformer.create())
                .subscribe(new ObserverContainer<>(mCompositeDisposable, new BaseObserver<OpenMeteoLocationResults>() {
                    @Override
                    public void onSucceed(OpenMeteoLocationResults openMeteoLocationResults) {
                        if (openMeteoLocationResults.getResults() != null && openMeteoLocationResults.getResults().size() != 0) {
                            List<Location> locationList = new ArrayList<>();
                            for (OpenMeteoLocationResult r : openMeteoLocationResults.getResults()) {
                                locationList.add(OpenMeteoResultConverterKt.convert(null, r, WeatherSource.MF));
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

    protected String getToken(Context context) {
        if (!SettingsManager.getInstance(context).getProviderMfWsftKey().equals(BuildConfig.MF_WSFT_KEY)) {
            return SettingsManager.getInstance(context).getProviderMfWsftKey();
        } else {
            try {
                JwtBuilder jwtsBuilder = Jwts.builder();
                jwtsBuilder.setHeaderParam(Header.TYPE, Header.JWT_TYPE);

                HashMap<String, String> claims = new HashMap<>();
                claims.put("class", "mobile");
                claims.put(Claims.ISSUED_AT, String.valueOf(new Date().getTime() / 1000));
                claims.put(Claims.ID, UUID.randomUUID().toString());
                jwtsBuilder.setClaims(claims);

                byte[] keyBytes = BuildConfig.MF_WSFT_JWT_KEY.getBytes(StandardCharsets.UTF_8);
                jwtsBuilder.signWith(Keys.hmacShaKeyFor(keyBytes), SignatureAlgorithm.HS256);
                return jwtsBuilder.compact();
            } catch (Exception ignored) {
                return BuildConfig.MF_WSFT_KEY;
            }
        }
    }

    @Override
    public Boolean isConfigured(Context context) {
        return !TextUtils.isEmpty(getToken(context));
    }

    protected String getUserAgent() {
        return "okhttp/4.9.2";
    }
}