# Admission contract — milestone 002, subtasks 15/16

`AdmissionStore` owns one private `AdmissionPersistence` backend. Create one
store per backend; do not share a file between independently cached stores.
The host owns opening failures and must report them rather than substitute an
empty in-memory backend. Missing storage supplies no evidence; every installed
artifact then requires current admission evaluation.

## Artifact binding and authority

Admission binds `(packageName, apkSha256)` and now requires matching
`InstallationFingerprint(baseApkPath, firstInstallTimeMillis, lastUpdateTimeMillis)`
as supplementary installation-continuity evidence. Signer, version, bundle version,
filter level, timestamp and exception are historical metadata. A stale record
does not admit new bytes, even with the same signer/version. Inspection uses
`admissionOf`; binding checks use `hasAdmissionRecord(packageName, apkSha256,
installationFingerprint)`. A missing fingerprint never matches, even if the
package/hash match. Timestamp or path evidence alone never authorizes admission.

The user explicitly accepted that this fingerprint is not a unique installation
ID. Indistinguishable missed lifecycle transitions can retain admission, and
legitimate relocation can conservatively require fresh admission. No firmware
changes or SDK-generated installation UUID are assumed.

Admission alone never grants runtime eligibility, approval, Android permissions
or protected API access. Every runtime evaluation still checks current policy
and blocks. Unchanged normally admitted artifacts do not lose admission merely
because their stamp certificate was subsequently revoked. Cryptographic stamp
verification, admission and current policy remain separate inputs.

`toolId` is nullable because explicit bypasses can admit an unstamped APK.
Such records require `exception`; never invent a Light tool identity. Exceptions
record the actual admission path, not current settings. When a bypass ends,
evaluate normal provenance and current policy: an old bypass record cannot
manufacture a valid stamp or approval. Emulator development-key admission
remains Signed-only. The exception enum itself grants no authorization.

## Install and migration

The installer captures an `AdmissionEvaluation` from the verified staged bytes
and current admission policy. Persist these inputs with the install session if
callbacks must survive process death. Call `recordSuccessfulInstall` only after
PackageManager confirms successful installation of those exact bytes. Failed
installation must never call it. Recheck the installed artifact binding when
handling a delayed callback; a later replacement must not inherit this record.

`admitInstalled(packageName, apkSha256, installationFingerprint, evaluateCurrent)` is the single lazy
fallback for both pre-existing installs and interrupted admission writes.
It calls the supplied current admission evaluation only when the exact record
is absent. That evaluation must include current stamp trust/revocation, bundle,
filter and actual Android signer. Only Allow with a matching record is written.
Package/byte/signer read failures must refuse admission, never default to Allow.
Exceptions thrown by the fallback evaluator return `EvaluationFailed`, without
writing a record. Consumers must handle that outcome as unavailable admission.
No migration flag or alternate migration rule. Missing current fingerprint
returns EvaluationFailed; a successful write also requires a fingerprint.
The fallback's captured record must match both exact hash and fingerprint.

On Android, obtain base APK path and both timestamps from current PackageInfo.
Archive/staging metadata is not installed metadata. `LightSdkServer.ApkMetadata`
now includes firstInstallTime; ToolTrustRuntime constructs the live fingerprint
and never takes it from the cryptographic cache. Positive timestamps and an
absolute base path are required. Explicit execution bypasses still work when
normal metadata is unavailable, without creating approval.

Track 2 install integration must attach a current installed fingerprint only
after confirmed success and exact-byte/signer verification. Fresh candidate
evaluation must not grandfather a prior installed admission. If a delayed
callback cannot establish continuity with its journaled install, re-evaluate
the current artifact under current admission rules or refuse it; do not attach
an old Allow to whichever identical-byte installation happens to exist now.

`removeAdmission(packageName)` is the approved uninstall extension. It durably
removes a package record and is idempotent. Removal write failure closes the
store until reopened, just like admission write failure. Call on confirmed
uninstall (including the system package-removal path), excluding temporary
update-removal events marked replacing. Invalidate cryptographic cache on both.
An identical-byte reinstall must then pass fresh admission under current stamp
trust. Hosts must initialize the runtime before processing lifecycle events;
missed events while the host is unavailable still require reconciliation.

If policy changes between successful install and admission persistence, a crash
can leave an unrecorded artifact denied on retry (including stamp revocation).
This is the accepted install/write race. PersistenceFailed is not admission;
do not expose a successfully installed but unrecorded tool as authorized.

## Persistence

`FileAdmissionPersistence` writes LAD2 snapshots containing optional fingerprint
fields, and reads LAD1 snapshots as records with missing fingerprints. Legacy
records remain inspectable but cannot grandfather admission. They must pass the
same current-admission fallback before a fingerprint-bound record is written.
A failed refresh/persistence operation does not upgrade legacy evidence.
Older LAD1-only readers reject LAD2; coordinate consumer upgrades before rollout.

The versioned binary snapshot has a SHA-256
corruption checksum, distinct from bundle and stamp-cache storage. The checksum
detects accidental corruption; private host storage supplies access control.
Each snapshot contains one record per package. Invalid, truncated, duplicate or
unsupported data throws during open; callers must not erase evidence and retry
as empty. No automatic corruption repair or implicit authorization.

Writes serialize to a same-directory temporary file, force file contents,
atomically replace the destination, then force the directory. Unsupported
atomic replacement fails; no non-atomic fallback. A crash before replacement
retains the previous snapshot; orphan temp files are never read as authority.
A failure after replacement can leave the new file present with uncertain
durability. Any write failure closes the in-memory store to admission until
the host explicitly reopens durable storage. Existing packages/data remain.

## Integration gates

Consumers 07/08/09/10/14 must use this contract rather than define competing
records. 16 is implemented by the shared fallback; device integration/tests
remain with those consumers. Phase 0 tests cover binding, lazy migration,
revocation during the crash window, write failure, bypass roundtrip, corrupt
storage, and unchanged historical policy metadata. Real PackageInstaller,
platform persistence/power-loss and runtime bypass-removal tests remain device
integration work. No hardware validation is claimed.
