//package com.example.landscape_view_recognition
//
//import android.content.Context
//import android.hardware.camera2.CameraCharacteristics
//import android.hardware.camera2.CameraManager
//
//
//class ViewerDeviceInfo {
//
//
//
//
//}
//
//private fun calculateFieldOfView(cameraId: String): Pair<Float, Float> {
//
//    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
//    val characteristics = manager.getCameraCharacteristics(cameraId)
//
//    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
//    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
//
//    if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
//        val focalLength = focalLengths[0] // Typically, the first one is the primary focal length.
//
//        // Calculate the field of view
//        val horizontalView = Math.toDegrees(2 * Math.atan2(sensorSize.width.toDouble(), (2 * focalLength).toDouble())).toFloat()
//        val verticalView = Math.toDegrees(2 * Math.atan2(sensorSize.height.toDouble(), (2 * focalLength).toDouble())).toFloat()
//
//        return Pair(horizontalView, verticalView)
//    }
//
//    return Pair(0f, 0f)
//}