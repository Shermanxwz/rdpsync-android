#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || exit 1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/reference-evidence"
uid=$(cat "$OUT/owned-fixture-uid")
[[ "$uid" =~ ^[0-9]+$ && "$uid" -gt 0 && "$(id -u rdpfixture)" == "$uid" ]] || exit 2
reset_fixture() {
  # This account was created by this job; never touches a developer or arbitrary PID.
  sudo pkill -TERM -u "$uid" || true
  for unused in $(seq 1 100); do
    if ! sudo pgrep -u "$uid" >/dev/null && ! curl -fsS --max-time 1 http://127.0.0.1:8765/state >/dev/null 2>&1; then return; fi
    sleep 0.2
  done
  echo 'Owned fixture session did not terminate; refusing stale-state comparison.' >&2
  return 1
}
printf 'flush\tsynchronous_dynamic_channels\texit_code\n' >"$OUT/results.tsv"
for flush in TRUE FALSE; do
  for sync in FALSE TRUE; do
    reset_fixture
    profile="$OUT/flush-$flush-sync-$sync"
    mkdir -p "$profile"
    set +e
    bash "$ROOT/scripts/fixtures/run-reference-resize.sh" "$flush" "$sync" "$profile" >"$profile/runner.log" 2>&1
    result=$?
    set -e
    printf '%s\t%s\t%s\n' "$flush" "$sync" "$result" | tee -a "$OUT/results.tsv"
    sudo cp /var/log/xrdp.log /var/log/xrdp-sesman.log "$profile/" || true
    sudo cp /home/rdpfixture/rdpsync-fixture-state.json /home/rdpfixture/fixture.log "$profile/" || true
    # Glob as the fixture owner, and use non-hidden artifact names. The runner
    # cannot glob this account's private home; upload-artifact excludes dotfiles.
    while IFS= read -r xlog; do
      sudo cp "$xlog" "$profile/xorg-${xlog##*/}"
    done < <(sudo find /home/rdpfixture -maxdepth 1 -type f -name '.xorgxrdp.*.log')
    sudo chown -R "$(id -u):$(id -g)" "$profile"
  done
done
reset_fixture
# Raw failures remain in results.tsv/exit-code.txt and actual child status in
# process-result.json. Only the exact documented old-server failure is a valid
# negative control; it is never labelled a successful old-server resize.
python3 "$ROOT/scripts/fixtures/verify_reference_results.py" "$OUT"
