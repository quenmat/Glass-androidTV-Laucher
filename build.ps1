# Build Glass Home thành build\glass-home.apk (không cần Gradle).
# Cần: JDK 17 (Microsoft OpenJDK) và Android SDK có build-tools 34 + platforms android-34
# ở %LOCALAPPDATA%\Android\Sdk.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"; $bt = "$sdk\build-tools\34.0.0"; $jar = "$sdk\platforms\android-34\android.jar"
$jdk = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" | Select-Object -First 1 -ExpandProperty FullName
if (-not $jdk) { throw "Khong thay JDK 17" }
$env:JAVA_HOME = $jdk; $env:Path = "$jdk\bin;$env:Path"

$b = "$root\build"; Remove-Item -Recurse -Force $b -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$b\classes", "$b\dex" | Out-Null

function Run($exe, [string[]]$a) {
  $ErrorActionPreference = "Continue"
  & $exe @a 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -and $_ -notmatch '^warning|Note:' }
  if ($LASTEXITCODE -ne 0) { throw "$exe loi ($LASTEXITCODE)" }
}

$srcs = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object FullName
Run javac (@("-encoding", "UTF-8", "--release", "11", "-classpath", $jar, "-d", "$b\classes") + $srcs)
$classes = Get-ChildItem "$b\classes" -Recurse -Filter *.class | ForEach-Object FullName
Run "$bt\d8.bat" (@("--release", "--lib", $jar, "--min-api", "31", "--output", "$b\dex") + $classes)

Run "$bt\aapt2.exe" @("compile", "--dir", "$root\res", "-o", "$b\res.zip")
Run "$bt\aapt2.exe" @("link", "-I", $jar, "--manifest", "$root\AndroidManifest.xml",
  "-o", "$b\unsigned.apk", "$b\res.zip")

Copy-Item "$b\dex\classes.dex" "$b\classes.dex"
Push-Location $b; Run jar @("uf", "unsigned.apk", "classes.dex"); Pop-Location
# assets đóng bằng jar (không nén, đường dẫn dùng "/"): aapt2 -A trên Windows ghi "\" làm Android không đọc được
New-Item -ItemType Directory -Force "$b\stage" | Out-Null
Copy-Item -Recurse "$root\assets" "$b\stage\assets"
Run jar @("-u", "-0", "-f", "$b\unsigned.apk", "-C", "$b\stage", "assets")
Run "$bt\zipalign.exe" @("-f", "4", "$b\unsigned.apk", "$b\aligned.apk")

# khoá ký riêng cho repo (tạo một lần, giữ lại để cập nhật đè được)
$ks = "$root\glass-home.keystore"
if (-not (Test-Path $ks)) {
  Run keytool @("-genkeypair", "-keystore", $ks, "-storepass", "glasshome", "-keypass", "glasshome",
    "-alias", "glass", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Glass Home")
}
Run "$bt\apksigner.bat" @("sign", "--ks", $ks, "--ks-pass", "pass:glasshome", "--key-pass", "pass:glasshome",
  "--out", "$b\glass-home.apk", "$b\aligned.apk")
"OK -> $b\glass-home.apk ($([math]::Round((Get-Item "$b\glass-home.apk").Length / 1KB)) KB)"
