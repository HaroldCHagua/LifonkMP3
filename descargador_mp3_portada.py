"""
Descargador de audio MP3 con portada incrustada.
Úsalo únicamente con contenido propio, de dominio público o autorizado.
Requisitos: Python 3.10+, yt-dlp y FFmpeg.
"""

import os
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from pathlib import Path

try:
    import yt_dlp
except ImportError:
    yt_dlp = None


class MusicDownloader:
    def __init__(self, root):
        self.root = root
        self.root.title("Descargador MP3 con portada")
        self.root.geometry("610x300")
        self.root.minsize(520, 280)

        self.url_var = tk.StringVar()
        self.folder_var = tk.StringVar(
            value=str(Path.home() / "DescargasMusica")
        )
        self.status_var = tk.StringVar(value="Pega un enlace para comenzar.")
        self.progress_var = tk.DoubleVar(value=0)

        main = ttk.Frame(root, padding=18)
        main.pack(fill="both", expand=True)

        ttk.Label(main, text="Enlace de YouTube:").pack(anchor="w")
        ttk.Entry(main, textvariable=self.url_var).pack(fill="x", pady=(5, 12))

        ttk.Label(main, text="Carpeta de destino:").pack(anchor="w")
        folder_row = ttk.Frame(main)
        folder_row.pack(fill="x", pady=(5, 12))
        ttk.Entry(folder_row, textvariable=self.folder_var).pack(
            side="left", fill="x", expand=True
        )
        ttk.Button(folder_row, text="Elegir…", command=self.choose_folder).pack(
            side="left", padx=(8, 0)
        )

        self.download_button = ttk.Button(
            main, text="Descargar MP3 con portada", command=self.start_download
        )
        self.download_button.pack(fill="x", pady=(2, 12))

        self.progress = ttk.Progressbar(
            main, variable=self.progress_var, maximum=100, mode="determinate"
        )
        self.progress.pack(fill="x")
        ttk.Label(main, textvariable=self.status_var, wraplength=560).pack(
            anchor="w", pady=(9, 0)
        )

    def choose_folder(self):
        folder = filedialog.askdirectory(
            title="Elige dónde guardar los MP3",
            initialdir=self.folder_var.get() or str(Path.home())
        )
        if folder:
            self.folder_var.set(folder)

    def start_download(self):
        url = self.url_var.get().strip()
        if not url or not (
            "youtube.com/" in url or "youtu.be/" in url
        ):
            messagebox.showwarning(
                "Enlace no válido",
                "Pega un enlace válido de YouTube."
            )
            return

        if yt_dlp is None:
            messagebox.showerror(
                "Falta yt-dlp",
                "Instálalo ejecutando:\npython -m pip install -U yt-dlp"
            )
            return

        folder = Path(self.folder_var.get()).expanduser()
        try:
            folder.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            messagebox.showerror("Carpeta no válida", str(exc))
            return

        self.download_button.config(state="disabled")
        self.progress_var.set(0)
        self.status_var.set("Preparando descarga…")
        threading.Thread(
            target=self.download, args=(url, folder), daemon=True
        ).start()

    def update_status(self, message, progress=None):
        def update():
            self.status_var.set(message)
            if progress is not None:
                self.progress_var.set(max(0, min(100, progress)))
        self.root.after(0, update)

    def download(self, url, folder):
        def progress_hook(data):
            if data.get("status") == "downloading":
                total = data.get("total_bytes") or data.get("total_bytes_estimate")
                downloaded = data.get("downloaded_bytes", 0)
                percent = downloaded * 100 / total if total else 0
                self.update_status(
                    f"Descargando… {percent:.1f}%", percent
                )
            elif data.get("status") == "finished":
                self.update_status("Audio descargado. Añadiendo portada y metadatos…", 100)

        options = {
            "format": "bestaudio/best",
            "outtmpl": str(folder / "%(title).180B [%(id)s].%(ext)s"),
            "noplaylist": True,
            "writethumbnail": True,
            "windowsfilenames": True,
            "ignoreerrors": False,
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                },
                {"key": "FFmpegMetadata"},
                {"key": "EmbedThumbnail"},
            ],
            "progress_hooks": [progress_hook],
            "quiet": True,
            "no_warnings": True,
        }

        try:
            with yt_dlp.YoutubeDL(options) as downloader:
                info = downloader.extract_info(url, download=True)
                title = info.get("title", "Audio") if info else "Audio"
            self.update_status(f"¡Listo! Guardado en: {folder}", 100)
            self.root.after(
                0,
                lambda: messagebox.showinfo(
                    "Descarga terminada",
                    f"Se descargó «{title}» en:\n{folder}\n\n"
                    "La portada se incrusta en el MP3 si el vídeo ofrece una miniatura."
                )
            )
        except Exception as exc:
            self.update_status("No se pudo completar la descarga.")
            self.root.after(
                0, lambda err=str(exc): messagebox.showerror(
                    "Error de descarga",
                    f"{err}\n\nComprueba la conexión y que FFmpeg esté instalado."
                )
            )
        finally:
            self.root.after(0, lambda: self.download_button.config(state="normal"))


if __name__ == "__main__":
    root = tk.Tk()
    app = MusicDownloader(root)
    root.mainloop()
