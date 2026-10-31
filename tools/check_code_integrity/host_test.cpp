// The native code comparison (code_integrity.cpp) run on this machine: the
// acceptance of the release review of 2026-10-31 for its items 1, 2 and 5.
// From the repository root:
//
//   clang++ -std=c++17 -DNECESSARY_HOST_TEST -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
//       tools/check_code_integrity/host_test.cpp -o /tmp/check_code_integrity -lpthread \
//       && /tmp/check_code_integrity
//
// What it holds:
//   - the snapshot parser (executable, file-backed mappings; long lines whole);
//   - item 2: a memory map that cannot be read, an empty one, and one that maps
//     no target each answer checked false with their reason, no bucket complete;
//   - item 1: an execute-only mapping (--xp, as Android 10+ maps system
//     libraries) is made readable for the copy only and its exact protection
//     put back; a byte changed in it is found; two threads hammering the
//     comparison are never inside it at once;
//   - item 5: the app region is the app's own APKs and the named libraries --
//     another package's APK is not the app's -- and the app's own executable
//     files outside the region are counted and named.
#include "../../necessary/src/main/cpp/code_integrity.cpp"
#include <cassert>
#include <thread>

namespace {

std::string fixture_dir() {
    char path[] = "/tmp/code_integrity_host_XXXXXX";
    assert(mkdtemp(path) != nullptr);
    return path;
}

std::string write_file(const std::string &path, const std::string &content) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    assert(fd >= 0);
    assert(write(fd, content.data(), content.size()) == (ssize_t) content.size());
    close(fd);
    return path;
}

// The protection /proc/self/maps shows for the mapping that starts at `start`.
std::string perms_at(unsigned long start) {
    std::string maps;
    assert(read_maps(&maps));
    for (const Mapping &m : executable_mappings(maps)) {
        if (m.start == start) return m.perms;
    }
    return "";
}

bool has(const std::string &json, const std::string &part) {
    return json.find(part) != std::string::npos;
}

}  // namespace

int main() {
    // The parser: executable, file-backed mappings only, paths with spaces kept, long lines whole.
    std::string long_path = "/data/app/" + std::string(3000, 'x') + "/base.apk";
    std::string maps =
        "7f00000000-7f00001000 r-xp 00000000 fd:00 1 /system/lib64/libc.so\n"
        "7f00001000-7f00002000 r--p 00001000 fd:00 1 /system/lib64/libc.so\n"
        "7f00002000-7f00003000 --xp 00002000 fd:00 2 /system/lib64/libart.so\n"
        "7f00003000-7f00004000 r-xp 00000000 00:00 0 \n"
        "7f00004000-7f00005000 r-xp 00000000 fd:00 3 /data/app/my app/lib/arm64/libapp.so\n"
        "7f00005000-7f00006000 r-xp 00010000 fd:00 4 " + long_path + "\n"
        "garbage line\n";
    std::vector<Mapping> m = executable_mappings(maps);
    assert(m.size() == 4);
    assert(m[0].path == "/system/lib64/libc.so" && m[0].start == 0x7f00000000UL);
    assert(m[1].path == "/system/lib64/libart.so" && m[1].perms[0] == '-');
    assert(m[2].path == "/data/app/my app/lib/arm64/libapp.so");
    assert(m[3].path == long_path && m[3].offset == 0x10000);

    std::string dir = fixture_dir();
    AppOwn nobody;

    // Item 2: no inventory, no completeness -- each with its reason.
    std::string unreadable = inspect((dir + "/no-such-maps").c_str(), nobody);
    assert(has(unreadable, "\"checked\":false,\"reason\":\"maps_unreadable\""));
    std::string empty = inspect(write_file(dir + "/empty-maps", "").c_str(), nobody);
    assert(has(empty, "\"checked\":false,\"reason\":\"maps_empty\""));
    std::string none = inspect(write_file(dir + "/no-target-maps",
        "7f00000000-7f00001000 r-xp 00000000 fd:00 9 /system/lib64/libnothing.so\n").c_str(), nobody);
    assert(has(none, "\"checked\":false,\"reason\":\"no_target_mapped\""));
    for (const std::string &json : {unreadable, empty, none}) {
        assert(!has(json, "_complete\":true"));
        assert(has(json, "\"app_outside_count\":0"));
    }

    // Item 5, the selection alone (crafted maps, nothing compared): the app's
    // own APKs and named libraries are its region; another package's APK is
    // not; the app's own executable files outside the region are named.
    AppOwn own{{"/data/app/~~a==/com.x-b==/base.apk", "/data/app/~~a==/com.x-b==/split_config.arm64_v8a.apk"},
               {"/data/app/~~a==/com.x-b==/", "/data/user/0/com.x/"}};
    std::vector<Mapping> crafted = executable_mappings(
        "1000-2000 r-xp 00000000 fd:00 1 /data/app/~~a==/com.x-b==/base.apk\n"
        "2000-3000 r-xp 00000000 fd:00 2 /data/app/~~a==/com.x-b==/split_config.arm64_v8a.apk\n"
        "3000-4000 r-xp 00000000 fd:00 3 /data/app/~~w==/com.google.android.trichromelibrary-1==/base.apk\n"
        "4000-5000 r-xp 00000000 fd:00 4 /data/app/~~a==/com.x-b==/lib/arm64/libplugin.so\n"
        "5000-6000 r-xp 00000000 fd:00 5 /data/app/~~a==/com.x-b==/oat/arm64/base.odex\n"
        "6000-7000 r-xp 00000000 fd:00 6 /data/user/0/com.x/files/libdownloaded.so\n"
        "7000-8000 r-xp 00000000 fd:00 7 /data/app/~~a==/com.x-b==/lib/arm64/libnecessary.so\n");
    std::vector<bool> chosen(crafted.size(), false);
    for (const Target &target : app_targets(own)) {
        for (size_t i = 0; i < crafted.size(); i++) {
            if (selects(target, crafted[i].path)) chosen[i] = true;
        }
    }
    assert(chosen[0] && chosen[1] && !chosen[2] && !chosen[3] && !chosen[4] && !chosen[5] && chosen[6]);
    std::set<std::string> outside = outside_files(crafted, chosen, own);
    assert(outside.size() == 3);  // libplugin.so, base.odex, libdownloaded.so -- not the WebView APK
    assert(outside.count("/data/app/~~a==/com.x-b==/oat/arm64/base.odex") == 1);

    // Item 1: an execute-only fixture, compared through the app region and put back.
    const size_t page = (size_t) sysconf(_SC_PAGESIZE);
    std::string content(3 * page, '\0');
    for (size_t i = 0; i < content.size(); i++) content[i] = (char) (i * 31 + 7);
    std::string apk = write_file(dir + "/fixture.apk", content);
    int fd = open(apk.c_str(), O_RDONLY);
    void *mapped = mmap(nullptr, content.size(), PROT_EXEC, MAP_PRIVATE, fd, 0);
    assert(mapped != MAP_FAILED);
    close(fd);
    unsigned long start = (unsigned long) mapped;
    assert(perms_at(start) == "--xp");
    AppOwn fixture{{apk}, {dir + "/"}};
    std::string clean = inspect("/proc/self/maps", fixture);
    assert(has(clean, "\"checked\":true"));
    assert(has(clean, "\"app_expected_bytes\":" + std::to_string(content.size())));
    assert(has(clean, "\"app_compared_bytes\":" + std::to_string(content.size())));
    assert(has(clean, "\"app_diff_bytes\":0") && has(clean, "\"app_complete\":true"));
    assert(has(clean, "\"protect_restore_failures\":0"));
    assert(perms_at(start) == "--xp");  // the exact protection is back

    // A byte changed in memory is found, under the APK's name.
    assert(mprotect(mapped, page, PROT_READ | PROT_WRITE) == 0);
    ((volatile unsigned char *) mapped)[100] ^= 0xff;
    assert(mprotect(mapped, page, PROT_EXEC) == 0);
    std::string hooked = inspect("/proc/self/maps", fixture);
    assert(has(hooked, "\"app_diff_bytes\":1") && has(hooked, "\"diffed_libs\":\"fixture.apk\""));
    assert(perms_at(start) == "--xp");

    // Two threads hammering the comparison: never inside it at once.
    g_max_in_flight = 0;
    auto hammer = [&]() { for (int i = 0; i < 25; i++) inspect("/proc/self/maps", fixture); };
    std::thread a(hammer), b(hammer);
    a.join();
    b.join();
    assert(g_max_in_flight.load() == 1);
    assert(perms_at(start) == "--xp");

    munmap(mapped, content.size());
    printf("code_integrity host test: parser 4 of 4; maps unreadable, empty, no target -> checked false "
           "with reason; app region own APKs only, 3 own files outside named; execute-only fixture "
           "compared (%zu bytes) and restored to --xp; 1 changed byte found; 2 threads x 25, never "
           "overlapping\n", content.size());
    return 0;
}
