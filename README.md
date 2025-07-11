# Washing Machine Notification System

A free solution to get notifications when your washing machine is done using audio detection and Firebase Cloud Messaging.

## 🏠 Project Overview

This system uses your old Android phone as a sensor to detect when your washing machine finishes its cycle and sends push notifications to your devices.

### Architecture
- **Android App (Kotlin)**: Audio detection sensor
- **React Web Dashboard**: Real-time monitoring interface
- **Node.js Server**: Backend API and notification system
- **Firebase**: Cloud messaging and database

## 🚀 Quick Start

### Prerequisites
- Android Studio
- Node.js 16+
- Google Account (for Firebase)
- Old Android phone (Android 6.0+)

### Setup Instructions
1. Follow the detailed setup guide: [SETUP_INSTRUCTIONS.md](./SETUP_INSTRUCTIONS.md)
2. Complete Firebase project setup
3. Run the implementation plan: [PROJECT_PLAN.md](./PROJECT_PLAN.md)

## 📁 Project Structure

```
washing-machine-notifier/
├── android-app/              # Kotlin Android sensor app
├── web-dashboard/            # React.js web interface
├── server/                   # Node.js backend
├── docs/                     # Documentation
├── scripts/                  # Build and deployment scripts
├── PROJECT_PLAN.md          # Implementation roadmap
├── SETUP_INSTRUCTIONS.md    # Setup guide
└── README.md                # This file
```

## 🛠️ Technology Stack

### Frontend
- **Android**: Kotlin, Android SDK
- **Web**: React.js, Material-UI, Socket.io-client

### Backend
- **Server**: Node.js, Express.js, Socket.io
- **Database**: Firebase Firestore
- **Notifications**: Firebase Cloud Messaging

### Development Tools
- **Android**: Android Studio
- **Web**: VS Code, Create React App
- **Server**: VS Code, nodemon
- **Version Control**: Git

## 🎯 Features

### Android App
- Continuous audio recording
- Real-time sound analysis
- Background service
- Firebase FCM integration
- Battery optimization

### Web Dashboard
- Real-time device monitoring
- Notification history
- Device management
- Settings configuration
- Beautiful UI/UX

### Server
- REST API endpoints
- WebSocket support
- Firebase integration
- Authentication
- Error handling

## 💰 Cost

**Total Cost: $0**
- Firebase: Free tier (25,000 messages/month)
- Android Studio: Free
- Node.js server: Free to host locally
- Old Android phone: Already owned

## 📋 Implementation Status

- [x] **Phase 1.1**: Development Environment Setup
- [ ] **Phase 1.2**: Firebase Project Setup
- [ ] **Phase 1.3**: Project Structure
- [ ] **Phase 2**: Android App Development
- [ ] **Phase 3**: Node.js Server Development
- [ ] **Phase 4**: React Web Dashboard
- [ ] **Phase 5**: Integration & Testing
- [ ] **Phase 6**: Deployment & Production

## 🔧 Development

### Running the System
1. **Start the server:**
   ```bash
   cd server
   npm start
   ```

2. **Start the web dashboard:**
   ```bash
   cd web-dashboard
   npm start
   ```

3. **Run the Android app:**
   - Open Android Studio
   - Open the android-app project
   - Click "Run" (green play button)

### Development Workflow
- Follow the implementation plan in `PROJECT_PLAN.md`
- Test each component individually
- Use separate terminal windows for different services
- Check the setup instructions for detailed guidance

## 📚 Documentation

- [Setup Instructions](./SETUP_INSTRUCTIONS.md) - Detailed setup guide
- [Project Plan](./PROJECT_PLAN.md) - Implementation roadmap
- [API Documentation](./docs/api-documentation.md) - API reference
- [Troubleshooting](./docs/troubleshooting.md) - Common issues and solutions

## 🤝 Contributing

1. Follow the project plan
2. Test your changes thoroughly
3. Update documentation as needed
4. Report issues with detailed information

## 📄 License

This project is open source and available under the MIT License.

## 🆘 Support

If you encounter issues:
1. Check the troubleshooting guide
2. Review the setup instructions
3. Check the project plan for implementation details
4. Create an issue with detailed information

---

**Happy washing! 🧺** 