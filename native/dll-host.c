#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <io.h>
#include <fcntl.h>

/* json-utf8-v1 adapter ABI, x64. Vendor-specific ABI conversion belongs in an
 * adapter DLL. stdout from vendor code is redirected to the instance log. */
typedef const char *(__cdecl *invoke_fn)(const char *);
typedef void (__cdecl *free_fn)(const char *);
typedef struct module { char *path; HMODULE handle; struct module *next; } module;
static module *modules;
static FILE *protocol;

static char *read_field(void) {
    uint32_t length;
    if (fread(&length, sizeof(length), 1, stdin) != 1 || length > 1024 * 1024) return NULL;
    char *value = malloc((size_t)length + 1);
    if (!value) return NULL;
    if (fread(value, 1, length, stdin) != length) { free(value); return NULL; }
    value[length] = 0;
    return value;
}

static void reply(uint32_t status, const char *value) {
    size_t size = strnlen(value, 4 * 1024 * 1024 + 1);
    if (size > 4 * 1024 * 1024) { status = 1; value = "DLL result exceeds 4 MiB"; size = strlen(value); }
    uint32_t length = (uint32_t)size;
    fwrite(&status, 4, 1, protocol);
    fwrite(&length, 4, 1, protocol);
    fwrite(value, 1, length, protocol);
    fflush(protocol);
}

static HMODULE load_module(const char *path) {
    int count = 0;
    for (module *m = modules; m; m = m->next) {
        if (strcmp(m->path, path) == 0) return m->handle;
        count++;
    }
    if (count >= 64) return NULL;
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (!length) return NULL;
    wchar_t *wide = calloc((size_t)length, sizeof(wchar_t));
    if (!wide) return NULL;
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wide, length);
    HMODULE handle = LoadLibraryExW(wide, NULL, LOAD_WITH_ALTERED_SEARCH_PATH);
    free(wide);
    if (handle) {
        module *m = calloc(1, sizeof(module));
        if (!m) { FreeLibrary(handle); return NULL; }
        m->path = _strdup(path); m->handle = handle; m->next = modules; modules = m;
    }
    return handle;
}

int main(void) {
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
    protocol = _fdopen(_dup(_fileno(stdout)), "wb");
    if (!protocol) return 2;
    _dup2(_fileno(stderr), _fileno(stdout));
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    fwrite("HTPCDLL1", 1, 8, protocol); fflush(protocol);
    for (;;) {
        char *path = read_field();
        if (!path) break;
        char *name = read_field(), *free_name = read_field(), *input = read_field();
        if (!name || !free_name || !input) { free(path); free(name); free(free_name); free(input); break; }
        HMODULE handle = load_module(path);
        invoke_fn invoke = handle ? (invoke_fn)GetProcAddress(handle, name) : NULL;
        free_fn release = handle && *free_name ? (free_fn)GetProcAddress(handle, free_name) : NULL;
        if (!handle) reply(1, "Cannot load DLL (check architecture and dependent DLLs)");
        else if (!invoke) reply(1, "DLL export does not exist");
        else if (*free_name && !release) reply(1, "DLL free export does not exist");
        else {
            const char *result = invoke(input);
            if (!result) reply(1, "DLL returned a null pointer");
            else { reply(0, result); if (release) release(result); }
        }
        free(path); free(name); free(free_name); free(input);
    }
    while (modules) { module *m = modules; modules = m->next; FreeLibrary(m->handle); free(m->path); free(m); }
    fclose(protocol);
    return 0;
}
