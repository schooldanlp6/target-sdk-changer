# AOSP framework references

Robolectric's `android-all` jars: the AOSP framework (`android.*`,
`com.android.internal.*`, no Java core library) of one Android release,
as plain Java class files. Source: Maven Central,
`org.robolectric:android-all:<version>`. Each download is checked against
the published SHA-1.

Used by `tools/Porter.java`:

- as the **reference** of a `generic-*` board (what the phone has), and
- as the **source** of ported code (Android 10 and 11).

| file | Android | API | role | present |
|---|---|---|---|---|
| android-all-5.0.2_r3-robolectric-r0.jar | 5.0 | 21 | generic-5 reference | yes |
| android-all-5.1.1_r9-robolectric-r2.jar | 5.1 | 22 | generic-5.1 reference | yes |
| android-all-6.0.1_r3-robolectric-r1.jar | 6.0 | 23 | generic-6 reference | yes |
| android-all-7.0.0_r1-robolectric-r1.jar | 7.0 | 24 | generic-7 reference | yes |
| android-all-7.1.0_r7-robolectric-r1.jar | 7.1 | 25 | generic-7.1 reference | yes |
| android-all-8.0.0_r4-robolectric-r1.jar | 8.0 | 26 | generic-8 / xa1-8 reference | yes |
| android-all-9-robolectric-4913185-2.jar | 9 | 28 | generic-9 reference | yes |
| android-all-10-robolectric-5803371.jar | 10 | 29 | source up to API 29 | yes |
| android-all-11-robolectric-6757853.jar | 11 | 30 | source up to API 30 | yes |

Download (example):

```sh
v=9-robolectric-4913185-2
curl -fO https://repo1.maven.org/maven2/org/robolectric/android-all/$v/android-all-$v.jar
curl -f https://repo1.maven.org/maven2/org/robolectric/android-all/$v/android-all-$v.jar.sha1; sha1sum android-all-$v.jar
```
