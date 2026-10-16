Optional SDKs (NOT included - Steinberg licenses apply):

  third_party/asiosdk/    ASIO SDK  (must contain iasiodrv.h, asio.h, asiosys.h; they may also sit in a "common" sub-folder)
  third_party/vst3sdk/    VST3 SDK  (must contain pluginterfaces/, public.sdk/, base/)

Without them the app still builds and runs (WASAPI only, no VST3). Details: README_WINDOWS.md
