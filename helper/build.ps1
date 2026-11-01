# Build app phụ "Đổi ảnh nền" (vn.glass.allapps, trước tên "Tất cả ứng dụng") -> helper\build\allapps.apk
# Ký bằng helper.keystore (giữ lại để cài đè lên bản đang có trên box, không phải gán lại nút).
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $bt = "$sdk\build-tools\34.0.0"; $jar = "$sdk\platforms\android-34\android.jar"
$jdk = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" | Select-Object -First 1 -ExpandProperty FullName
$env:JAVA_HOME = $jdk; $env:Path = "$jdk\bin;$env:Path"
$b = "$here\build"; Remove-Item -Recurse -Force $b -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$b\classes", "$b\res\values" | Out-Null

function Run($exe, [string[]]$a) {
  $ErrorActionPreference = "Continue"
  & $exe @a 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -and $_ -notmatch '^warning|Note:' }
  if ($LASTEXITCODE -ne 0) { throw "$exe loi ($LASTEXITCODE)" }
}

Run javac @("-encoding", "UTF-8", "--release", "11", "-classpath", $jar, "-d", "$b\classes", "$here\src\vn\dock\L.java")
Run "$bt\d8.bat" @("--release", "--lib", $jar, "--min-api", "26", "--output", $b, "$b\classes\vn\dock\L.class")
'<resources/>' | Set-Content "$b\res\values\v.xml"
@"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="vn.glass.allapps" android:versionCode="4" android:versionName="4">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
  <queries>
    <intent><action android:name="vn.glass.home.ALL_APPS"/></intent>
    <intent><action android:name="tv.projectivy.ALL_APPS"/></intent>
  </queries>
  <!-- nút APPS trên điều khiển (gán qua Button Mapper) mở app này -> Glass Home sang ảnh nền kế tiếp -->
  <application android:label="&#272;&#7893;i &#7843;nh n&#7873;n" android:allowBackup="false">
    <meta-data android:name="act" android:value="vn.glass.home.ALL_APPS,tv.projectivy.ALL_APPS"/>
    <activity android:name="vn.dock.L" android:exported="true" android:theme="@android:style/Theme.NoDisplay" android:excludeFromRecents="true" android:noHistory="true">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
    </activity>
  </application>
</manifest>
"@ | Set-Content -Encoding utf8 "$b\AndroidManifest.xml"
Run "$bt\aapt2.exe" @("compile", "-o", "$b\res.zip", "--dir", "$b\res")
Run "$bt\aapt2.exe" @("link", "-I", $jar, "--manifest", "$b\AndroidManifest.xml", "-o", "$b\u.apk", "$b\res.zip")
Push-Location $b; Run jar @("uf", "u.apk", "classes.dex"); Pop-Location
Run "$bt\zipalign.exe" @("-f", "4", "$b\u.apk", "$b\a.apk")
Run "$bt\apksigner.bat" @("sign", "--ks", "$here\helper.keystore", "--ks-pass", "pass:dockvo", "--key-pass", "pass:dockvo", "--out", "$b\allapps.apk", "$b\a.apk")
"OK -> $b\allapps.apk"
