#!/usr/bin/env python3
"""Fetch selected CC-BY 4.0 aRTF photos with HTTP ranges, not the 7 GB archive.

Uses only the existing Python standard library and curl. Installs nothing.
Archive entries are read with zipfile (including CRC validation); no extraction
paths from the archive are used as local filesystem paths.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import pathlib
import subprocess
import tempfile
import zipfile

URL = "https://zenodo.org/api/records/10875542/files/aRTFClothes-rgb.zip/content"
SIZE = 7099723852
ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / "target" / "artf-ranges"


class RemoteArchive(io.RawIOBase):
    def __init__(self):
        self.position = 0
        self.cache = {}
        self.downloaded = 0

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=io.SEEK_SET):
        self.position = offset + (self.position if whence == io.SEEK_CUR else SIZE if whence == io.SEEK_END else 0)
        if self.position < 0:
            raise ValueError("negative seek")
        return self.position

    def fetch(self, start, end):
        CACHE.mkdir(parents=True, exist_ok=True)
        cached = CACHE / f"{start}-{end}.bin"
        if cached.exists() and cached.stat().st_size == end - start + 1:
            return cached.read_bytes()
        if end - start + 1 > 524288:
            ranges = [(offset, min(offset + 524287, end)) for offset in range(start, end + 1, 524288)]
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as chunks:
                return b"".join(chunks.map(lambda bounds: self.fetch(*bounds), ranges))
        with tempfile.TemporaryDirectory(prefix="closet-artf-") as folder:
            data = pathlib.Path(folder) / "bytes"
            headers = pathlib.Path(folder) / "headers"
            # Distinct cache keys prevent a proxy from reusing another byte range.
            url = f"{URL}?range_start={start}&range_end={end}"
            subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location",
                            "--connect-timeout", "15", "--max-time", "180", "--retry", "2",
                            "--max-filesize", str(end - start + 1), "--range", f"{start}-{end}",
                            "--dump-header", str(headers), "--output", str(data), url], check=True)
            expected = f"content-range: bytes {start}-{end}/{SIZE}"
            if expected not in headers.read_text().lower():
                raise RuntimeError("Server did not return the requested byte range")
            result = data.read_bytes()
            if len(result) != end - start + 1:
                raise RuntimeError("Incomplete range")
            self.downloaded += len(result)
            cached.write_bytes(result)
            return result

    def read(self, length=-1):
        if length == 0 or self.position >= SIZE:
            return b""
        if length < 0:
            length = SIZE - self.position
        length = min(length, SIZE - self.position)
        if length > 16 * 1024 * 1024:
            raise ValueError("Refusing oversized read; select individual images")
        if length <= 65536:
            start = self.position // 65536 * 65536
            end = min(SIZE - 1, max(start + 65535, self.position + length - 1))
            key = (start, end)
            if key not in self.cache:
                self.cache[key] = self.fetch(start, end)
            result = self.cache[key][self.position - start:self.position - start + length]
        else:
            key = (self.position, self.position + length - 1)
            if key not in self.cache:
                self.cache[key] = self.fetch(*key)
            result = self.cache[key]
        self.position += len(result)
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", help="JSON array: id, archiveSuffix (relative path ending), optional other metadata")
    parser.add_argument("--output", default="samples/round2")
    args = parser.parse_args()
    destination = ROOT / args.output
    destination.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(pathlib.Path(args.manifest).read_text())
    remote = RemoteArchive()
    with zipfile.ZipFile(remote) as archive:
        names = archive.namelist()
        entries = {entry.filename: entry for entry in archive.infolist()}

        def fetch_item(item):
            sample_id = item["id"]
            if not sample_id.replace("-", "").replace("_", "").isalnum():
                raise ValueError("Invalid local sample ID")
            matching = [name for name in names if name.endswith(item["archiveSuffix"])]
            if len(matching) != 1:
                raise ValueError(f"Expected one archive entry for {sample_id}, found {len(matching)}")
            entry = entries[matching[0]]
            if entry.file_size > 16 * 1024 * 1024:
                raise ValueError("Photo is too large")
            path = destination / f"{sample_id}.png"
            if not path.exists():
                reader = RemoteArchive()
                reader.cache = remote.cache
                with zipfile.ZipFile(reader) as local_archive:
                    payload = local_archive.read(entry.filename)
                path.write_bytes(payload)
            else:
                import zlib
                if zlib.crc32(path.read_bytes()) != entry.CRC:
                    raise ValueError(f"Existing file does not match archive: {path}")
            item["archiveEntry"] = entry.filename
            item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            print(f"Saved {sample_id}: {path.stat().st_size} bytes", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(fetch_item, manifest))
    (destination / "download-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Selected images downloaded via ranges and checked against ZIP CRC", flush=True)


if __name__ == "__main__":
    main()
