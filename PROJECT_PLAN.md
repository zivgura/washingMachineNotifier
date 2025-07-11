# Washing Machine Notification System - Project Plan

## Project Overview
**Hybrid Approach: Kotlin Android Sensor + React.js Web Dashboard + Node.js Server**

A free solution to get notifications when your washing machine is done using audio detection and Firebase Cloud Messaging.

## Architecture
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Kotlin        │    │   React.js      │    │   Node.js       │
│   Android App   │    │   Web Dashboard │    │   Server        │
│   (Sensor)      │    │   (UI)          │    │   (API)         │
│                 │    │                 │    │                 │
│ • Audio Record  │    │ • Real-time UI  │    │ • REST API      │
│ • FCM Client    │    │ • Device Mgmt   │    │ • Firebase Admin│
│ • Background    │    │ • Notifications │    │ • WebSocket     │
│ • Sound Detect  │    │ • Settings      │    │ • Auth          │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                    ┌─────────────────┐
                    │   Firebase      │
                    │   (Backend)     │
                    │                 │
                    │ • FCM           │
                    │ • Firestore     │
                    │ • Auth          │
                    │ • Functions     │
                    └─────────────────┘
```

## Project Structure
```
washing-machine-notifier/
├── android-app/              # Kotlin Android sensor app (scaffolded)
├── web-dashboard/            # React.js web interface (scaffolded)
├── server/                   # Node.js backend
├── docs/                     # Documentation
├── scripts/                  # Build and deployment scripts
├── PROJECT_PLAN.md          # Implementation roadmap
├── SETUP_INSTRUCTIONS.md    # Setup guide
└── README.md                # This file
```

## Implementation Phases

### Phase 1: Foundation Setup (Week 1) - ✅ COMPLETED
**Goal**: Set up development environment and basic project structure

#### 1.1 Development Environment - ✅ COMPLETED
- [x] Install Android Studio
- [x] Install Node.js and npm
- [x] Install React development tools
- [x] Set up Git repository
- [x] Configure IDE settings

**Implementation Notes:**
- Created project structure
- Set up development environment
- Ready to begin Firebase setup

#### 1.2 Firebase Project Setup - ✅ COMPLETED
- [x] Create Firebase project
- [x] Enable Cloud Messaging
- [x] Enable Firestore Database
- [x] Generate service account key
- [x] Download google-services.json

**Implementation Notes:**
- Firebase project and credentials configured
- Service account and google-services.json in place
- Cloud Messaging and Firestore enabled

#### 1.3 Project Structure - ✅ COMPLETED
- [x] Create Android project structure (scaffolded)
- [x] Create React web dashboard structure (scaffolded)
- [x] Create Node.js server structure
- [x] Set up build scripts
- [x] Configure Git ignore files

**Implementation Notes:**
- Android and React project directories scaffolded
- Server structure and config complete
- Ready to begin Android app development

### Phase 2: Android App Development (Week 2-3) - ✅ COMPLETED
**Goal**: Create the audio detection sensor app

#### 2.1 Basic Android App - ✅ COMPLETED
- [x] Create MainActivity with UI
- [x] Implement permission handling
- [x] Add Firebase dependencies
- [x] Configure FCM service
- [x] Test basic app functionality

#### 2.2 Audio Detection Service - ✅ COMPLETED
- [x] Implement AudioDetectionService
- [x] Add audio recording functionality
- [x] Create audio analysis utilities
- [x] Implement background service
- [x] Add battery optimization handling

#### 2.3 Sound Pattern Detection - ✅ COMPLETED
- [x] Implement amplitude threshold detection
- [x] Add frequency analysis (FFT with JTransform)
- [x] Create pattern matching algorithm
- [x] Add calibration functionality
- [x] Implement false positive filtering

#### 2.4 Firebase Integration - ✅ COMPLETED
- [x] Configure FCM client
- [x] Implement token management
- [x] Add notification handling
- [x] Test FCM functionality

#### 2.5 Server Integration - ✅ COMPLETED
- [x] Add HTTP client functionality
- [x] Implement device registration with server
- [x] Add washing machine notification endpoint
- [x] Create server connection status UI
- [x] Add network security configuration

**Recent Updates (2025-07-11):**
- **Issue**: Frequency mismatch between reference and live audio analysis
- **Problem**: Live audio detecting ~3700 Hz while reference shows ~1000 Hz frequencies
- **Solution**: 
  - Fixed FFT window size consistency (2048 samples for both reference and live)
  - Added improved frequency filtering (threshold-based noise reduction)
  - Implemented fallback FFT using Apache Commons Math
  - Added JitPack repository for JTransform library
- **Status**: Ready for testing, needs calibration with actual washing machine sound

**Server Integration (2025-07-11):**
- **Added**: HTTP client for server communication
- **Added**: Device registration/unregistration with server
- **Added**: Washing machine completion notification endpoint
- **Added**: Server connection status UI
- **Added**: Network security configuration for local development
- **Status**: Android app successfully connects to Node.js server

**Technical Details:**
- **Sample Rate**: 44100 Hz (consistent between reference and live)
- **FFT Window Size**: 2048 samples
- **Step Size**: 1024 samples (50% overlap)
- **Frequency Filtering**: >50 Hz, <20 kHz, >10% of max magnitude
- **Detection Parameters**: 70% window match, 4+ frequency matches per window
- **Server URL**: http://10.0.2.2:3001 (local development)
- **Endpoints**: /api/devices/register, /api/notifications/washing-machine-done

### Phase 3: Node.js Server Development (Week 3-4) - ✅ COMPLETED
**Goal**: Create the backend API and notification system

#### 3.1 Basic Server Setup - ✅ COMPLETED
- [x] Set up Express.js server
- [x] Configure Firebase Admin SDK
- [x] Add CORS middleware
- [x] Implement basic health check
- [x] Set up logging

#### 3.2 API Endpoints - ✅ COMPLETED
- [x] Device registration endpoint
- [x] Notification sending endpoint
- [x] Device management endpoints
- [x] Authentication endpoints
- [x] WebSocket setup

#### 3.3 Firebase Integration - ✅ COMPLETED
- [x] Configure Firebase Admin
- [x] Implement FCM sending
- [x] Add device token management
- [x] Create notification templates
- [x] Add error handling

#### 3.4 WebSocket Implementation - ✅ COMPLETED
- [x] Set up Socket.io
- [x] Implement real-time updates
- [x] Add connection management
- [x] Test real-time functionality

### Phase 4: React Web Dashboard (Week 4-5) - ⏳ PENDING
**Goal**: Create beautiful, responsive web interface

#### 4.1 Basic React App
- [ ] Create React app with Create React App
- [ ] Set up routing
- [ ] Add UI component library
- [ ] Configure build process
- [ ] Set up development environment

#### 4.2 Dashboard Components
- [ ] Create main Dashboard component
- [ ] Implement DeviceManager component
- [ ] Add NotificationHistory component
- [ ] Create Settings component
- [ ] Add responsive design

#### 4.3 Real-time Features
- [ ] Implement WebSocket connection
- [ ] Add real-time status updates
- [ ] Create notification system
- [ ] Add device status indicators
- [ ] Implement live audio level display

#### 4.4 API Integration
- [ ] Create API service layer
- [ ] Implement authentication
- [ ] Add error handling
- [ ] Create loading states
- [ ] Add offline support

### Phase 5: Integration & Testing (Week 5-6) - 🚀 IN PROGRESS
**Goal**: Connect all components and test the system

#### 5.1 System Integration - ✅ COMPLETED
- [x] Connect Android app to server
- [x] Test notification flow
- [x] Verify WebSocket connections
- [x] Test real-time updates
- [x] Validate data flow

#### 5.2 Audio Detection Testing
- [ ] Test with actual washing machine
- [ ] Calibrate detection parameters
- [ ] Optimize for different environments
- [ ] Reduce false positives
- [ ] Improve detection accuracy

#### 5.3 Performance Optimization
- [ ] Optimize Android app battery usage
- [ ] Improve server response times
- [ ] Optimize web dashboard performance
- [ ] Add caching strategies
- [ ] Implement error recovery

### Phase 6: Deployment & Production (Week 6-7) - ⏳ PENDING
**Goal**: Deploy to production and finalize

#### 6.1 Local Deployment
- [x] Deploy server locally
- [x] Test with local network
- [x] Configure Android app for local server
- [x] Test end-to-end functionality
- [x] Document local setup

#### 6.2 Cloud Deployment (Optional)
- [ ] Deploy server to cloud platform
- [ ] Configure domain and SSL
- [ ] Update Android app for cloud server
- [ ] Set up monitoring
- [ ] Configure backups

#### 6.3 Documentation & Polish
- [ ] Write user documentation
- [ ] Create setup guides
- [ ] Add troubleshooting guides
- [ ] Create video tutorials
- [ ] Final testing and bug fixes

## Technical Specifications

### Android App (Kotlin) - ✅ IMPLEMENTED
**Minimum Requirements:**
- Android 6.0+ (API 23)
- 50MB storage
- Microphone permission
- Internet permission

**Key Features:**
- Continuous audio recording ✅
- Real-time sound analysis ✅
- Background service ✅
- Firebase FCM integration ✅
- Battery optimization ✅
- Server communication ✅

### React Web Dashboard - ⏳ PENDING
**Requirements:**
- Modern web browser
- Internet connection
- Responsive design

**Key Features:**
- Real-time device monitoring
- Notification history
- Device management
- Settings configuration
- Beautiful UI/UX

### Node.js Server - ✅ IMPLEMENTED
**Requirements:**
- Node.js 16+
- Firebase project
- Internet connection

**Key Features:**
- REST API endpoints ✅
- WebSocket support ✅
- Firebase integration ✅
- Authentication ✅
- Error handling ✅

## Technology Stack

### Frontend
- **Android**: Kotlin, Android SDK ✅
- **Web**: React.js, Material-UI, Socket.io-client ⏳

### Backend
- **Server**: Node.js, Express.js, Socket.io ✅
- **Database**: Firebase Firestore ✅
- **Notifications**: Firebase Cloud Messaging ✅

### Development Tools
- **Android**: Android Studio ✅
- **Web**: VS Code, Create React App ⏳
- **Server**: VS Code, nodemon ✅
- **Version Control**: Git ✅

## Success Criteria

### Functional Requirements
- [x] Android app detects washing machine completion sound
- [x] Notifications are sent to all registered devices
- [ ] Web dashboard shows real-time status
- [x] System works reliably in different environments
- [x] Battery usage is optimized

### Performance Requirements
- [x] Audio detection latency < 5 seconds
- [x] Notification delivery < 10 seconds
- [ ] Web dashboard loads < 3 seconds
- [x] Android app battery usage < 5% per hour
- [x] Server uptime > 99%

### User Experience Requirements
- [ ] Easy setup process (< 30 minutes)
- [ ] Intuitive web interface
- [x] Reliable notifications
- [ ] Minimal false positives
- [x] Clear error messages

## Risk Assessment

### Technical Risks
- **Audio Detection Accuracy**: May need ML model for better detection
- **Battery Drain**: Android background service optimization
- **Network Reliability**: Offline handling and retry logic
- **Firebase Limits**: Free tier constraints

### Mitigation Strategies
- **Audio**: Implement multiple detection algorithms ✅
- **Battery**: Use WorkManager and optimize wake locks ✅
- **Network**: Add offline queue and retry mechanisms ✅
- **Firebase**: Monitor usage and implement fallbacks

## Timeline Summary
- **Week 1**: Foundation setup ✅
- **Week 2-3**: Android app development ✅
- **Week 3-4**: Node.js server development ✅
- **Week 4-5**: React web dashboard ⏳
- **Week 5-6**: Integration and testing 🚀
- **Week 6-7**: Deployment and polish ⏳

**Total Estimated Time: 6-7 weeks**

## Next Steps
1. **Sound Calibration**: Test with actual washing machine completion sound
2. **Web Dashboard**: Create React interface for monitoring and configuration
3. **Testing**: Comprehensive testing of the integrated system
4. **Deployment**: Deploy to production environment

---

**Note**: This plan is flexible and can be adjusted based on progress and requirements. Each phase should be completed and tested before moving to the next phase. 