#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdlib.h>
#include <string.h>

/* Replace the body with conversion to a vendor DLL's actual ABI. Return UTF-8
 * JSON allocated by this module, and release it through this module's free. */
__declspec(dllexport) const char *__cdecl echo_json(const char *input) {
    return _strdup(input);
}
__declspec(dllexport) void __cdecl free_json(const char *output) {
    free((void *)output);
}
__declspec(dllexport) const char *__cdecl slow_json(const char *input) {
    Sleep(5000);
    return "null";
}
__declspec(dllexport) const char *__cdecl invalid_json(const char *input) {
    return "not json";
}
