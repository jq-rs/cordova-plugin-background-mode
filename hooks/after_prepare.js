#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

module.exports = function(context) {
    // Update AndroidManifest.xml
    const manifestPath = path.join(
        context.opts.projectRoot,
        'platforms/android/app/src/main/AndroidManifest.xml'
    );

    if (fs.existsSync(manifestPath)) {
        let manifest = fs.readFileSync(manifestPath, 'utf8');

        // Match any ForegroundService declaration (with or without process attribute)
        const serviceRegex = /<service[^>]*android:name="de\.appplant\.cordova\.plugin\.background\.ForegroundService"[^>]*\/>/g;
        
        const newService = '<service android:name="de.appplant.cordova.plugin.background.ForegroundService" android:process=":background" android:enabled="true" android:exported="false" android:foregroundServiceType="remoteMessaging" />';

        // Count matches
        const matches = manifest.match(serviceRegex);
        
        if (matches && matches.length > 0) {
            // Remove all existing declarations
            manifest = manifest.replace(serviceRegex, '');
            
            // Add our single declaration before </application>
            manifest = manifest.replace('</application>', '        ' + newService + '\n    </application>');
            
            fs.writeFileSync(manifestPath, manifest, 'utf8');
            console.log('ForegroundService: replaced ' + matches.length + ' declaration(s) with single process=":background" version');
        }
    }

    // Add OkHttp dependency to build.gradle
    const buildGradlePath = path.join(
        context.opts.projectRoot,
        'platforms/android/app/build.gradle'
    );

    if (fs.existsSync(buildGradlePath)) {
        let buildGradle = fs.readFileSync(buildGradlePath, 'utf8');
        
        const okHttpLib = "implementation 'com.squareup.okhttp3:okhttp:5.3.2'";
        const siphashLib = "implementation 'io.whitfin:siphash:3.0.0'";
        
        if (!buildGradle.includes('okhttp3:okhttp')) {
            // Add after SUB-PROJECT DEPENDENCIES END
            buildGradle = buildGradle.replace(
                '// SUB-PROJECT DEPENDENCIES END',
                '// SUB-PROJECT DEPENDENCIES END\n    \n    // OkHttp for WebSocket\n    ' + okHttpLib
            );
            fs.writeFileSync(buildGradlePath, buildGradle, 'utf8');
            console.log('OkHttp library added to build.gradle');
        }
        
        if (!buildGradle.includes('whitfin:siphash')) {
            // Add after SUB-PROJECT DEPENDENCIES END
            buildGradle = buildGradle.replace(
                '// SUB-PROJECT DEPENDENCIES END',
                '// SUB-PROJECT DEPENDENCIES END\n    \n    // Siphash for chksumming\n    ' + siphashLib
            );
            fs.writeFileSync(buildGradlePath, buildGradle, 'utf8');
            console.log('Siphash library added to build.gradle');
        }
    }
};
