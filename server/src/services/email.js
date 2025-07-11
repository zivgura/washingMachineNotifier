const nodemailer = require('nodemailer');

class EmailService {
  constructor() {
    this.transporter = null;
    this.isConfigured = false;
    this.initializeTransporter();
  }

  initializeTransporter() {
    // Gmail SMTP configuration
    const emailConfig = {
      service: 'gmail',
      auth: {
        user: process.env.EMAIL_USER,
        pass: process.env.EMAIL_PASSWORD
      }
    };

    // Only create transporter if email credentials are provided
    if (emailConfig.auth.user && emailConfig.auth.pass) {
      this.transporter = nodemailer.createTransport(emailConfig);
      this.isConfigured = true;
      console.log('✅ Email service configured');
    } else {
      console.log('⚠️ Email service not configured. Set EMAIL_USER and EMAIL_PASSWORD environment variables.');
    }
  }

  async sendWashingMachineNotification(recipientEmail = null) {
    if (!this.isConfigured) {
      console.log('Email service not configured, skipping email notification');
      return { success: false, message: 'Email service not configured' };
    }

    try {
      const emailTo = recipientEmail || process.env.EMAIL_USER;
      
      const mailOptions = {
        from: process.env.EMAIL_USER,
        to: emailTo,
        subject: '🧺 Washing Machine Done!',
        html: `
          <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
            <h2 style="color: #2196F3;">🧺 Washing Machine Notification</h2>
            <p>Your washing machine has finished its cycle!</p>
            <div style="background-color: #f5f5f5; padding: 15px; border-radius: 5px; margin: 20px 0;">
              <p><strong>Time:</strong> ${new Date().toLocaleString()}</p>
              <p><strong>Status:</strong> Cycle completed</p>
            </div>
            <p>Don't forget to move your clothes to the dryer or hang them up!</p>
            <hr style="margin: 20px 0;">
            <p style="color: #666; font-size: 12px;">
              This notification was sent by your Washing Machine Notification System.
            </p>
          </div>
        `,
        text: `
          🧺 Washing Machine Done!
          
          Your washing machine has finished its cycle!
          
          Time: ${new Date().toLocaleString()}
          Status: Cycle completed
          
          Don't forget to move your clothes to the dryer or hang them up!
        `
      };

      const result = await this.transporter.sendMail(mailOptions);
      console.log('✅ Email notification sent successfully');
      return { success: true, messageId: result.messageId };
    } catch (error) {
      console.error('❌ Failed to send email notification:', error);
      return { success: false, error: error.message };
    }
  }

  async sendTestEmail(recipientEmail = null) {
    if (!this.isConfigured) {
      console.log('Email service not configured, skipping test email');
      return { success: false, message: 'Email service not configured' };
    }

    try {
      const emailTo = recipientEmail || process.env.EMAIL_USER;
      
      const mailOptions = {
        from: process.env.EMAIL_USER,
        to: emailTo,
        subject: '🧪 Test Notification - Washing Machine System',
        html: `
          <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
            <h2 style="color: #FF9800;">🧪 Test Notification</h2>
            <p>This is a test email from your Washing Machine Notification System.</p>
            <div style="background-color: #fff3e0; padding: 15px; border-radius: 5px; margin: 20px 0;">
              <p><strong>Test Time:</strong> ${new Date().toLocaleString()}</p>
              <p><strong>Status:</strong> Email service working correctly</p>
            </div>
            <p>If you received this email, your notification system is working properly!</p>
            <hr style="margin: 20px 0;">
            <p style="color: #666; font-size: 12px;">
              This is a test notification from your Washing Machine Notification System.
            </p>
          </div>
        `,
        text: `
          🧪 Test Notification
          
          This is a test email from your Washing Machine Notification System.
          
          Test Time: ${new Date().toLocaleString()}
          Status: Email service working correctly
          
          If you received this email, your notification system is working properly!
        `
      };

      console.log('Attempting to send email to:', emailTo);
      console.log('Using email config:', {
        user: process.env.EMAIL_USER,
        pass: process.env.EMAIL_PASSWORD ? '***SET***' : '***NOT SET***'
      });

      const result = await this.transporter.sendMail(mailOptions);
      console.log('✅ Test email sent successfully');
      return { success: true, messageId: result.messageId };
    } catch (error) {
      console.error('❌ Failed to send test email:', error);
      console.error('Error details:', {
        code: error.code,
        command: error.command,
        response: error.response,
        responseCode: error.responseCode
      });
      return { success: false, error: error.message };
    }
  }

  getStatus() {
    return {
      configured: this.isConfigured,
      email: this.isConfigured ? process.env.EMAIL_USER : null
    };
  }
}

module.exports = new EmailService(); 