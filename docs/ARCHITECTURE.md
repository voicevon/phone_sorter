# System Architecture

## Overview
The Asparagus Grading System is split into a mobile frontend (Android) and a vision processing backend (Python/OpenCV).

## Core Components

### 1. Vision Engine (`src/vision/`)
A modular Python package providing the core analysis logic.
- **Detector**: Marker recognition and image segmentation.
- **Measurer**: Physical dimension calculation and feature detection.
- **Grader**: Business logic application for grading.
- **Processor**: High-level orchestration of the vision pipeline.

### 2. Android Client (`src/android/`)
Native Kotlin application for capturing images and interacting with the user.
- **Camera Manager**: Handles device camera preview and captures.
- **UI & Overlay**: Displays real-time feedback and grading results.
- **Integration Layer**: Interfaces with the vision processing (via local or server-side execution).

### 3. Documentation (`docs/`)
Clean, maintainable knowledge base for the project.

## Data Flow
1. User captures a photo via the Android UI.
2. The image is processed to identify ARUCO markers.
3. Perspective transform is applied based on markers to normalize the view.
4. Asparagus features (contour, purple root) are detected.
5. Dimensions are measured at specific offsets.
6. Grading logic is applied.
7. Results are returned for UI display and voice broadcast.
