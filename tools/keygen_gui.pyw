"""NoAmp FX - License Key Generator (window version). Double-click run_keygen.bat or this file."""
import tkinter as tk
from tkinter import messagebox

try:
    import license_tool
    from license_tool import make_key, normalize_code
except ModuleNotFoundError as e:  # cryptography missing
    root = tk.Tk(); root.withdraw()
    messagebox.showerror("NoAmp FX", f"{e}\n\nOpen Command Prompt and run:\n    pip install cryptography")
    raise SystemExit(1)

root = tk.Tk()
root.title("NoAmp FX")
root.resizable(False, False)

name_v, id_v, key_v, status_v = tk.StringVar(), tk.StringVar(), tk.StringVar(), tk.StringVar()
FONT = ("Segoe UI", 10)

for r, (label, var, ro) in enumerate([("Registered Name", name_v, False), ("Device ID", id_v, False), ("License Key", key_v, True)]):
    tk.Label(root, text=label, font=FONT, anchor="w").grid(row=r, column=0, padx=(12, 8), pady=8, sticky="w")
    e = tk.Entry(root, textvariable=var, width=52, font=("Consolas", 10), relief="sunken", bd=2,
                 state="readonly" if ro else "normal", readonlybackground="#e4e4e4")
    e.grid(row=r, column=1, padx=(0, 12), pady=8, ipady=3)
    if r == 0:
        name_e = e
    if r == 1:
        id_e = e


def generate(event=None):
    name = name_v.get().strip()
    if not name:
        messagebox.showwarning("NoAmp FX", "Enter the Registered Name (used for your records).")
        name_e.focus_set(); return
    try:
        key = make_key(id_v.get(), name)
    except (ValueError, FileNotFoundError) as e:
        messagebox.showerror("NoAmp FX", str(e)); return
    id_v.set(normalize_code(id_v.get()))
    key_v.set(key)
    root.clipboard_clear(); root.clipboard_append(key); root.update()
    status_v.set("License key generated and copied to clipboard." + (("  (" + license_tool.last_warning + ")") if license_tool.last_warning else ""))


tk.Label(root, textvariable=status_v, font=("Segoe UI", 9), fg="#2a7a2a", anchor="w").grid(row=3, column=0, columnspan=2, padx=12, sticky="w")
tk.Button(root, text="Generate", font=FONT, width=14, command=generate).grid(row=3, column=1, padx=12, pady=(4, 12), sticky="e")
root.bind("<Return>", generate)
name_e.focus_set()
root.mainloop()
