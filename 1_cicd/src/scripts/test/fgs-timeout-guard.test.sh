#!/usr/bin/env bash
# #fgs-timeout the fgs-timeout guard must be RED for each way a worker or service can run as a dataSync
# foreground service without timeout handling, and GREEN on a conforming tree. A fixture tree is planted per
# case; the real tree is checked by the workflow's next step.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/../cloud-android-fgs-timeout-guard.py"
fails=0
OUT="$(mktemp)"
mk() {
  local r; r="$(mktemp -d)"
  mkdir -p "$r/1_cicd/src/data" "$r/ac_a/app/src/main/java/a" "$r/ac_b/gradle" "$r/ac_x/src"
  echo '{"exempt_workers":{"ac_x/src/Share.kt":"upstream one-shot"}}' > "$r/1_cicd/src/data/fgs-timeout-guard.json"
  printf "android { packagingOptions { excludes += '**/*.so' } }\ndependencies {\n    implementation 'androidx.work:work-runtime-ktx:2.12.0'\n}\n" > "$r/ac_a/app/build.gradle"
  printf '[versions]\nwork = "2.12.0"\n[libraries]\nw = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }\n' > "$r/ac_b/gradle/libs.versions.toml"
  cat > "$r/ac_a/app/src/main/java/a/SyncWorker.kt" <<'K'
class SyncWorker : CoroutineWorker() {
    override suspend fun doWork(): Result {
        if (DataSyncBudget.mayHold(applicationContext, userInitiated = inputData.getBoolean("manual", false)))
            setForeground(info())
        return Result.success()
    }
    companion object { fun schedule() = PeriodicWorkRequestBuilder<SyncWorker>(30, MINUTES) }
}
K
  cat > "$r/ac_a/app/src/main/java/a/DlService.kt" <<'K'
class DlService : Service() {
    fun go() { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
}
K
  echo 'class Share : CoroutineWorker() { fun f() { setForegroundAsync(i) } }' > "$r/ac_x/src/Share.kt"
  echo "$r"
}
expect() { # name want-exit root
  python3 "$GUARD" "$3" > "$OUT" 2>&1; got=$?
  if [ "$got" -eq "$2" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $2)"; cat "$OUT"; fails=$((fails+1)); fi
  rm -rf "$3"
}
K="ac_a/app/src/main/java/a"
expect "a conforming tree is green" 0 "$(mk)"
r="$(mk)"; sed -i 's/2\.12\.0/2.9.1/' "$r/ac_a/app/build.gradle"; expect "W1 a Gradle pin below 2.10 is red (a '**/*.so' string earlier does not hide it)" 1 "$r"
r="$(mk)"; sed -i 's/"2\.12\.0"/"2.9.1"/' "$r/ac_b/gradle/libs.versions.toml"; expect "W1 a catalog version.ref below 2.10 is red" 1 "$r"
r="$(mk)"; printf '[libraries]\nw = { module = "androidx.work:work-runtime", version = "2.8.0" }\n' > "$r/ac_b/gradle/libs.versions.toml"; expect "W1 a catalog literal below 2.10 is red" 1 "$r"
r="$(mk)"; printf "dependencies {\n    // implementation 'androidx.work:work-runtime-ktx:2.9.1'\n}\n" > "$r/ac_a/app/build.gradle"; expect "W1 a commented-out old pin is green" 0 "$r"
r="$(mk)"; sed -i 's/DataSyncBudget.mayHold(applicationContext, userInitiated = inputData.getBoolean("manual", false))/true/' "$r/$K/SyncWorker.kt"; expect "W2 a worker that enters the foreground without the budget gate is red" 1 "$r"
r="$(mk)"; sed -i 's/^\(\s*\)if (DataSyncBudget/\1\/\/ if (DataSyncBudget/' "$r/$K/SyncWorker.kt"; expect "W2 a gate that is only in a comment is red" 1 "$r"
r="$(mk)"; sed -i 's/userInitiated = inputData.getBoolean("manual", false)/userInitiated = true/' "$r/$K/SyncWorker.kt"; expect "W3 a periodic worker holding as user-initiated (literal true) is red" 1 "$r"
r="$(mk)"; sed -i '/override fun onTimeout/d' "$r/$K/DlService.kt"; expect "W4 a dataSync service without onTimeout is red" 1 "$r"
r="$(mk)"; sed -i 's/override fun onTimeout(startId: Int, fgsType: Int)/override fun onTimeout(startId: Int)/' "$r/$K/DlService.kt"; expect "W4 only the one-argument (shortService) onTimeout is red" 1 "$r"
r="$(mk)"; printf 'public class J extends Service {\n  void go() { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }\n  @Override public void onTimeout(int startId, int fgsType) { stopSelf(); }\n}\n' > "$r/$K/J.java"; expect "W4 a Java service with onTimeout(int, int) is green" 0 "$r"
r="$(mk)"; echo 'class Share : CoroutineWorker() { }' > "$r/ac_x/src/Share.kt"; expect "W5 a stale exemption is red" 1 "$r"
r="$(mk)"; echo '{"exempt_workers":{}}' > "$r/1_cicd/src/data/fgs-timeout-guard.json"; expect "W2 the upstream worker without its exemption is red" 1 "$r"
rm -f "$OUT"
[ "$fails" -eq 0 ] && echo "fgs-timeout-guard.test: all green" || { echo "fgs-timeout-guard.test: $fails FAILED"; exit 1; }
