"""Break a shipped file into leaves: the smallest pieces whose origin can be proven.

A jar, an APK or an Android App Bundle is a zip file. Every file inside it is a leaf, and a
zip inside a zip (a jar inside a jar, say) is a leaf too, which can be opened in turn when
its own origin is not proven as a whole. Each leaf carries its SHA-256, so it can be
matched byte for byte against the files the build started from.
"""
import hashlib
import io
import zipfile
from dataclasses import dataclass

ARCHIVE_SUFFIXES = (".jar", ".zip", ".apk", ".aab", ".aar")


@dataclass(frozen=True)
class Leaf:
    path: str      # inside the artifact; a nested entry is "outer.jar!inner/name"
    sha256: str
    size: int
    kind: str      # what the bytes are, see kind_of

    @property
    def name(self):
        return self.path.rsplit("!", 1)[-1]


def kind_of(name, data):
    """Says what a file is from its first bytes, falling back to its name.

    The bytes decide where they can, because a name can lie: a native library is a native
    library whatever it is called, and that is the kind of file the old check walked past.
    """
    head = data[:4]
    if head == b"\xca\xfe\xba\xbe" and name.endswith(".class"):
        return "class"
    if head == b"\x7fELF":
        return "elf"
    if head[:2] == b"MZ":
        return "pe"
    if head in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"):
        return "macho"
    if data[:4] == b"dex\n":
        return "dex"
    if head == b"PK\x03\x04" or name.endswith(ARCHIVE_SUFFIXES):
        return "archive"
    if not data:
        return "empty"
    try:
        data.decode("utf-8")
        return "text"
    except UnicodeDecodeError:
        return "binary"


def leaves(source, prefix=""):
    """Every file in a zip, as Leaf objects. `source` is a path or the zip's bytes."""
    handle = io.BytesIO(source) if isinstance(source, bytes) else source
    with zipfile.ZipFile(handle) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            data = z.read(info)
            yield Leaf(prefix + info.filename, hashlib.sha256(data).hexdigest(), len(data),
                       kind_of(info.filename, data))


def read_entry(source, path):
    """The bytes of one leaf, following "outer!inner" through nested archives."""
    parts = path.split("!")
    data = None
    handle = source
    for part in parts:
        with zipfile.ZipFile(io.BytesIO(data) if data is not None else handle) as z:
            data = z.read(part)
    return data
