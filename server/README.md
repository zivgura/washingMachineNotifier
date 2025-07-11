# Washing Machine Notification Server

A Node.js server that receives notifications from an Android app when a washing machine finishes its cycle and sends email notifications.

## Features

- Receives HTTP notifications from Android app
- Sends email notifications via Gmail SMTP
- Firebase Cloud Messaging support (optional)
- Health check endpoint
- CORS enabled for cross-origin requests

## Environment Variables

Required environment variables:

```
EMAIL_USER=your-email@gmail.com
EMAIL_PASSWORD=your-gmail-app-password
PORT=3001
NODE_ENV=production
```

## API Endpoints

- `GET /api/health` - Health check
- `POST /api/notifications/washing-complete` - Washing machine completion notification
- `POST /api/notifications/test-email` - Test email notification
- `POST /api/devices/register` - Register device for FCM

## Deployment

This server is designed to be deployed on Render.com with automatic deployment from GitHub.

## Local Development

```bash
npm install
npm run dev
``` 