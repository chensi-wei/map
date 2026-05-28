package com.example.myapplication;

import org.osmdroid.views.overlay.Polyline;

public interface WhuRouteListener {
    void onRouteSuccess(Polyline routeLine);
    void onRouteFail(String reason);
}

