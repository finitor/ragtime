#ifndef RAGTIME_ANDROID_PICTURES_H
#define RAGTIME_ANDROID_PICTURES_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ragtime::android
{
// Inline pictures for the Android app, over the desktop's own codecs
// (image/): what goes on the air is a JPEG 2000 codestream (kind 0x01), and
// what comes from it is that or a Cool-chic stream (0x02), nothing else.
// The picture an operator picks is read by Android (JPEG, PNG, WebP,
// HEIF...) and handed over as pixels.
//
// None of this needs an AndroidClient or its worker: decoding a Cool-chic
// picture takes seconds, and must not hold the link up.

// This build can make and show pictures (false with -DRAGTIME_PICTURES=OFF).
bool picturesAvailable();

// Pixels are RGBA, 8 bits each, rows from the top; alpha is laid over white.
// The picture is shrunk to fit maxSide on its longer side, keeping the
// details a plain average loses (picture::downscale), and coded as a
// progressive JPEG 2000 codestream of at most maxBytes, so any first part
// of it shows the whole picture, softer. Empty, with the reason, if it
// can't be made that small.
std::vector<uint8_t> encodePicture(const uint8_t* rgba, int width, int height, int maxSide, uint64_t maxBytes,
                                   std::string& error);

// A picture's bytes, or as many as have come, to pixels: 4 bytes of width
// and 4 of height (little-endian), then RGBA rows from the top. Empty,
// with the reason, when there is no picture in them yet, they are neither
// kind, or the picture is bigger than maxSide either way.
std::vector<uint8_t> decodePicture(const uint8_t* bytes, size_t length, int maxSide, std::string& error);
} // namespace ragtime::android
#endif
