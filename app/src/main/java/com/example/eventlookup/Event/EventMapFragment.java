package com.example.eventlookup.Event;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.example.eventlookup.Event.Adapters.ImageSliderPageAdapter;
import com.example.eventlookup.Event.POJOs.EventFullPOJO;
import com.example.eventlookup.Shared.AppConf;
import com.example.eventlookup.Shared.CacheInterceptor;
import com.example.eventlookup.Shared.CustomMapView;
import com.example.eventlookup.Shared.MainThreadOkHttpCallback;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import com.example.eventlookup.R;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.maps.DirectionsApiRequest;
import com.google.maps.GeoApiContext;
import com.google.maps.PendingResult;
import com.google.maps.internal.PolylineEncoding;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.DirectionsRoute;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import okhttp3.Cache;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * A simple {@link Fragment} subclass.
 */
public class EventMapFragment extends Fragment implements OnMapReadyCallback, LocationListener, GoogleMap.OnPolylineClickListener {

    private final String FRAGMENT_MAP_TAG = "EventMapFragment";

    // ----- Vars -----

    // Google
    private GoogleMap mMap;
    private CustomMapView mMapView;
    private LatLngBounds mMapBounds;
    private GeoApiContext mGeoApiContext = null;
    private DirectionsResult mDirectionsResult = null;
    private ArrayList<PolylineData> mPolylineDataList = new ArrayList<>();
    private Marker currentSelectedMarker;

    // Location
    private LocationManager mLocationManager;
    private boolean mLocationPermissionGranted = false;
    private double mCurrentUserLat = 0.0;
    private double mCurrentUserLng = 0.0;
    private double mEventLat = 0.0;
    private double mEventLng = 0.0;
    private String mEventTitle = "";

    // Other
    private OkHttpClient okHttpClient;

    private String _eventId;

    public EventMapFragment() {
        // Required empty public constructor
    }

    public EventMapFragment(String eventId){
        this._eventId = eventId;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate( R.layout.fragment_event_map, container, false );
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated( view, savedInstanceState );

//        SupportMapFragment map = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.mapView);

//        map.getMapAsync( this );

        Bundle mapViewBundle = null;
        if (savedInstanceState != null) {
            mapViewBundle = savedInstanceState.getBundle( AppConf.MAPVIEW_BUNDLE_KEY );
        }
        if( mGeoApiContext == null){
            mGeoApiContext = new GeoApiContext.Builder()
                    .apiKey(AppConf.GOOGLE_MAPS_API_KEY)
                    .build();
        }

        try {
            getEventDetailedInfo();
        }
        catch (Exception e){
            Log.e(FRAGMENT_MAP_TAG, "Error while fetching data");
        }

        mMapView = view.findViewById(R.id.mapView);
        mMapView.onCreate(mapViewBundle);

        mMapView.getMapAsync(this);

        prepareButton( view );
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        Bundle mapViewBundle = outState.getBundle(AppConf.MAPVIEW_BUNDLE_KEY);
        if (mapViewBundle == null) {
            mapViewBundle = new Bundle();
            outState.putBundle(AppConf.MAPVIEW_BUNDLE_KEY, mapViewBundle);
        }

        mMapView.onSaveInstanceState(mapViewBundle);
    }

    private void initLocationManager(){
        /*
         * Request location permission, so that we can get the location of the
         * device. The result of the permission request is handled by a callback,
         * onRequestPermissionsResult.
         */
        if (ContextCompat.checkSelfPermission(getContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mLocationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(getActivity(),
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    AppConf.PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }

        mLocationManager = (LocationManager) getContext().getSystemService( Context.LOCATION_SERVICE );
        Location loc = mLocationManager.getLastKnownLocation( LocationManager.GPS_PROVIDER );
        this.mCurrentUserLat = loc.getLatitude();
        this.mCurrentUserLng = loc.getLongitude();
        if (mLocationManager != null)
            mLocationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, 0, 0, this );

    }

    // pull event location from db
    private void getEventDetailedInfo() throws Exception {
        File httpCacheDirectory = new File(getContext().getCacheDir(), "http-cache");
        int cacheSize = 10 * 1024 * 1024;
        Cache cache = new Cache( httpCacheDirectory, cacheSize );

        okHttpClient = new OkHttpClient.Builder(  ).addNetworkInterceptor( new CacheInterceptor() )
                .cache( cache )
                .build();
//        okHttpClient = new OkHttpClient(  );

        AppConf apiConf = AppConf.getInstance();
        String eventInfoRoute = apiConf.getEventGetEventDetailedApiRoute() + _eventId;

        HttpUrl.Builder urlBuilder = HttpUrl.parse(eventInfoRoute)
                .newBuilder();

        String url = urlBuilder.build()
                .toString();

        final Request request = new Request.Builder(  )
                .url( url )
                .build();

        okHttpClient.newCall(request).enqueue( new MainThreadOkHttpCallback() {

            @Override
            public void apiCallSuccess(String body){
                try{
                    JSONObject response = new JSONObject( body );
                    mEventTitle = response.getString( "Title" );

                    JSONObject address = response.getJSONObject( "Address" );
                    mEventLat = Double.parseDouble( address.getString( "Lat" ) );
                    mEventLng =  Double.parseDouble( address.getString( "Lng" ) );
                }
                catch (JSONException e){
                    Log.e("OkHttp", "Error while parsing api/event/{id} response data - " + e);
                }
            }

            @Override
            public void apiCallFail(Exception e){
                Log.e("OkHttp", "Api call http://<host>/api/event failed");
            }

        } );

    }

    private void zoomInCamera(List<LatLng> coords, GoogleMap googleMap){

        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();

        for(LatLng point : coords){
            boundsBuilder.include( point );
        }

        int routePadding = 300; // PX in map, to be able to see marker
        mMapBounds = boundsBuilder.build();

        googleMap.animateCamera( CameraUpdateFactory.newLatLngBounds( mMapBounds , routePadding ), 400,null );
    }

    private void calculateDirections(){
        com.google.maps.model.LatLng userDestination = new com.google.maps.model.LatLng(
                mEventLat,
                mEventLng
        );

        DirectionsApiRequest directionsApiRequest = new DirectionsApiRequest( mGeoApiContext );

        directionsApiRequest.alternatives( true );
        // current user coordinates
        directionsApiRequest.origin(
                new com.google.maps.model.LatLng(
                        mCurrentUserLat,
                        mCurrentUserLng
                )
        );

        directionsApiRequest.destination( userDestination ).setCallback( new PendingResult.Callback<DirectionsResult>() {
            @Override
            public void onResult(DirectionsResult result) {
                Log.d(FRAGMENT_MAP_TAG, result.routes[0].toString());
                if(result != null)
                    drawPolylines( result );
            }

            @Override
            public void onFailure(Throwable e) {
                Log.e(FRAGMENT_MAP_TAG, e.toString());
            }
        } );

    }

    private void drawPolylines(final DirectionsResult directionsResult){
        new Handler( Looper.getMainLooper()).post( new Runnable() {
            @Override
            public void run() {
                if(mPolylineDataList.size() > 0) {
                    for(PolylineData polylineData : mPolylineDataList)
                        polylineData.getPolyline().remove();
                    mPolylineDataList.clear();
                    mPolylineDataList = new ArrayList<>(  );
                }

                double tripDuration = 999999;
                for(DirectionsRoute route : directionsResult.routes){
                    List<com.google.maps.model.LatLng> decodedPath = PolylineEncoding.decode( route.overviewPolyline.getEncodedPath() );
                    List<LatLng> newDecodedPath = new ArrayList<>();

                    // Loops through all coordinates of ONE polyline
                    for(com.google.maps.model.LatLng latLng : decodedPath){
                        newDecodedPath.add( new LatLng(
                             latLng.lat,
                             latLng.lng
                        ) );
                    }

                    Polyline polyline = mMap.addPolyline( new PolylineOptions().addAll( newDecodedPath ) );
                    PolylineData polylineData = new PolylineData( polyline, route.legs[0] );
                    polyline.setColor(ContextCompat.getColor( getContext(), R.color.colorGrey ));
                    polyline.setClickable( true );
                    mPolylineDataList.add( polylineData );

                    double tempDuration = route.legs[0].duration.inSeconds;
                    if(tempDuration < tripDuration){
                        tripDuration = tempDuration;
                        onPolylineClick( polyline );
                    }
                }
            }
        } );
    }

    private void drawMarker(LatLng coords, PolylineData polylineData){
        currentSelectedMarker.setVisible( false );
        Marker marker = mMap.addMarker( new MarkerOptions()
                .position( coords )
                .title( mEventTitle )
                .snippet( "Trip duration: " + polylineData.getLeg().duration )
                .icon(BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_AZURE))
        );
        marker.showInfoWindow();
        currentSelectedMarker = marker;
    }

    private void prepareButton(View view){
        Button button = view.findViewById( R.id.event_direction_search );
        button.setOnClickListener( new Button.OnClickListener(){

            @Override
            public void onClick(View view) {
                calculateDirections(  );
            }
        } );
    }


        // --------------------------- OnMapReadyCallback interface ---------------------------
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        initLocationManager();

        LatLng userCurrentLocation = new LatLng(mCurrentUserLat, mCurrentUserLng);
        currentSelectedMarker = googleMap.addMarker(new MarkerOptions()
                .position(new LatLng(mEventLat, mEventLng))
                .title(mEventTitle)
                .icon(BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_AZURE)) );
        googleMap.setMyLocationEnabled( true );
        googleMap.setOnPolylineClickListener( this );

        calculateDirections();

        List<LatLng> coords = new ArrayList<>();
        coords.add( new LatLng( this.mCurrentUserLat, this.mCurrentUserLng ) );
        coords.add( new LatLng( this.mEventLat, this.mEventLng ) );
        zoomInCamera( coords, mMap );
    }

    @Override
    public void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    public void onStart() {
        super.onStart();
        mMapView.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        mMapView.onStop();
    }

    @Override
    public void onPause() {
        mMapView.onPause();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mMapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mMapView.onLowMemory();
    }
        // --------------------------- END OnMapReadyCallback interface ---------------------------


        // --------------------------- LocationListener interface ---------------------------
    @Override
    public void onLocationChanged(Location location) {
        if(location != null){
            this.mCurrentUserLat = location.getLatitude();
            this.mCurrentUserLng = location.getLongitude();
            mLocationManager.removeUpdates( this );
        }
    }
    // Required but not needed, because permissions are being checked at startup
    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) { }
    @Override
    public void onProviderEnabled(String s) { }
    @Override
    public void onProviderDisabled(String s) { }
        // ------------------------- END LocationListener interface ---------------------------



        // --------------------------- requestPermissions callback  ---------------------------
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        mLocationPermissionGranted = false;
        if(requestCode == AppConf.PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION){
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mLocationPermissionGranted = true;
            }
        }
    }

    // --------------------------- END requestPermissions callback  ---------------------------

    // --------------------------- OnPolylineClickListener callback ---------------------------
    @Override
    public void onPolylineClick(Polyline polyline) {
        for(PolylineData polylineData : mPolylineDataList){
            if(polyline.getId().equals( polylineData.getPolyline().getId() )){
                polylineData.getPolyline().setColor(ContextCompat.getColor( getContext(), R.color.colorAccentSoftRed ));
                polylineData.getPolyline().setZIndex( 1 );

                drawMarker( new LatLng( mEventLat, mEventLng ), polylineData );

                List<LatLng> coords = new ArrayList<>();
                coords.add( new LatLng( this.mCurrentUserLat, this.mCurrentUserLng ) );
                coords.add( new LatLng( this.mEventLat, this.mEventLng ) );
                zoomInCamera( coords, mMap );
            }
            else{
                polylineData.getPolyline().setColor(ContextCompat.getColor( getContext(), R.color.colorGrey ));
                polylineData.getPolyline().setZIndex( 0 );
            }
        }
    }
    // --------------------------- END OnPolylineClickListener callback ---------------------------

}
