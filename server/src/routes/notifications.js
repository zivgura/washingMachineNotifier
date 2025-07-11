const express = require('express');
const router = express.Router();
const { 
  sendNotificationToAll, 
  sendNotificationToDevice,
  storeNotification,
  getRecentNotifications
} = require('../services/firebase');
const emailService = require('../services/email');

// Send notification to all devices
router.post('/send', async (req, res) => {
  try {
    const { title, body, data = {} } = req.body;
    
    if (!title || !body) {
      return res.status(400).json({
        success: false,
        message: 'Title and body are required'
      });
    }

    // Send notification to all devices
    const response = await sendNotificationToAll(title, body, data);
    
    // Send email notification
    const emailResponse = await emailService.sendWashingMachineNotification();
    
    // Store notification in database
    await storeNotification({
      title,
      body,
      data,
      sentTo: response.successCount,
      totalDevices: response.successCount + response.failureCount,
      emailSent: emailResponse.success,
      timestamp: new Date()
    });

    res.json({
      success: true,
      message: `Notification sent to ${response.successCount} devices`,
      successCount: response.successCount,
      failureCount: response.failureCount,
      totalDevices: response.successCount + response.failureCount,
      emailSent: emailResponse.success,
      emailMessage: emailResponse.message
    });
  } catch (error) {
    console.error('Error sending notification:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to send notification',
      error: error.message
    });
  }
});

// Send notification to specific device
router.post('/send-to-device', async (req, res) => {
  try {
    const { token, title, body, data = {} } = req.body;
    
    if (!token || !title || !body) {
      return res.status(400).json({
        success: false,
        message: 'Token, title, and body are required'
      });
    }

    // Send notification to specific device
    const response = await sendNotificationToDevice(token, title, body, data);
    
    // Send email notification
    const emailResponse = await emailService.sendWashingMachineNotification();
    
    // Store notification in database
    await storeNotification({
      title,
      body,
      data,
      sentTo: 1,
      totalDevices: 1,
      targetDevice: token.substring(0, 20) + '...',
      emailSent: emailResponse.success,
      timestamp: new Date()
    });

    res.json({
      success: true,
      message: 'Notification sent successfully',
      response,
      emailSent: emailResponse.success,
      emailMessage: emailResponse.message
    });
  } catch (error) {
    console.error('Error sending notification to device:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to send notification to device',
      error: error.message
    });
  }
});

// Get recent notifications
router.get('/history', async (req, res) => {
  try {
    const { limit = 50 } = req.query;
    const notifications = await getRecentNotifications(parseInt(limit));
    
    res.json({
      success: true,
      count: notifications.length,
      notifications
    });
  } catch (error) {
    console.error('Error getting notification history:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get notification history',
      error: error.message
    });
  }
});

// Test notification endpoint
router.post('/test', async (req, res) => {
  try {
    const { title = 'Test Notification', body = 'This is a test notification' } = req.body;
    
    const response = await sendNotificationToAll(title, body, {
      type: 'test',
      timestamp: Date.now().toString()
    });
    
    // Send test email
    const emailResponse = await emailService.sendTestEmail();
    
    await storeNotification({
      title,
      body,
      data: { type: 'test' },
      sentTo: response.successCount,
      totalDevices: response.successCount + response.failureCount,
      emailSent: emailResponse.success,
      timestamp: new Date()
    });

    res.json({
      success: true,
      message: `Test notification sent to ${response.successCount} devices`,
      successCount: response.successCount,
      failureCount: response.failureCount,
      emailSent: emailResponse.success,
      emailMessage: emailResponse.message
    });
  } catch (error) {
    console.error('Error sending test notification:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to send test notification',
      error: error.message
    });
  }
});

// Washing machine completion notification
router.post('/washing-machine-done', async (req, res) => {
  try {
    const { detectedBy = 'unknown' } = req.body;
    
    const title = '🧺 Washing Machine Done!';
    const body = 'Your washing machine has finished its cycle.';
    
    const response = await sendNotificationToAll(title, body, {
      type: 'washing_machine_done',
      detectedBy,
      timestamp: Date.now().toString()
    });
    
    // Send email notification
    const emailResponse = await emailService.sendWashingMachineNotification();
    
    await storeNotification({
      title,
      body,
      data: { 
        type: 'washing_machine_done',
        detectedBy 
      },
      sentTo: response.successCount,
      totalDevices: response.successCount + response.failureCount,
      emailSent: emailResponse.success,
      timestamp: new Date()
    });

    res.json({
      success: true,
      message: `Washing machine notification sent to ${response.successCount} devices`,
      successCount: response.successCount,
      failureCount: response.failureCount,
      emailSent: emailResponse.success,
      emailMessage: emailResponse.message
    });
  } catch (error) {
    console.error('Error sending washing machine notification:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to send washing machine notification',
      error: error.message
    });
  }
});

// Test email endpoint
router.post('/test-email', async (req, res) => {
  try {
    const { email } = req.body;
    
    const emailResponse = await emailService.sendTestEmail(email);
    
    res.json({
      success: emailResponse.success,
      message: emailResponse.success ? 'Test email sent successfully' : 'Failed to send test email',
      emailMessage: emailResponse.message,
      emailStatus: emailService.getStatus()
    });
  } catch (error) {
    console.error('Error sending test email:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to send test email',
      error: error.message
    });
  }
});

// Get email service status
router.get('/email-status', async (req, res) => {
  try {
    const status = emailService.getStatus();
    
    res.json({
      success: true,
      emailConfigured: status.configured,
      emailAddress: status.email
    });
  } catch (error) {
    console.error('Error getting email status:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to get email status',
      error: error.message
    });
  }
});

module.exports = router; 