(function installMinimalPlaywrightCoreProbe(global) {
  const fs = require('fs');
  const path = require('path');
  const os = require('os');
  const childProcess = require('child_process');

  const packageMetadataPath = path.join('/package', 'package.json');
  const packageMetadataExists = fs.existsSync(packageMetadataPath);

  global.playwright = {
    __playwright4jProbe: {
      platform: os.platform(),
      packageMetadataExists: packageMetadataExists
    },
    chromium: {
      connectOverCDP: function (endpoint) {
        if (!endpoint) {
          throw new Error('endpoint is required');
        }

        return childProcess.spawn('browser-launch-is-not-implemented', []);
      }
    }
  };
})(globalThis);
