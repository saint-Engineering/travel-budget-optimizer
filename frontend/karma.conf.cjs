module.exports = function (config) {
    config.set({
        frameworks: ['jasmine'],
        plugins: [
            require('karma-jasmine'),
            require('karma-chrome-launcher'),
            require('karma-jasmine-html-reporter'),
            require('@angular/build/plugins/karma'),
        ],
        client: { clearContext: false },
        reporters: ['progress', 'kjhtml'],
        browsers: ['ChromeHeadless'],
        restartOnFileChange: true,
    });
};
