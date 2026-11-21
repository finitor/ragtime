#include "AndroidPictures.h"

#include <algorithm>

#include "ragtime/image/Codec.h"
#include "ragtime/image/CoolChic.h"
#include "ragtime/picture/Picture.h"

namespace ragtime::android
{
bool picturesAvailable() { return image::available(); }

std::vector<uint8_t> encodePicture(const uint8_t* rgba, int width, int height, int maxSide, uint64_t maxBytes,
                                   std::string& error)
{
    if (rgba == nullptr || width <= 0 || height <= 0 || (uint64_t)width * (uint64_t)height > 64u * 1024u * 1024u)
    {
        error = "There is no picture in that";
        return {};
    }
    picture::Image in = picture::Image::blank(width, height, 3);
    for (int y = 0; y < height; y++)
    {
        const uint8_t* from = rgba + (size_t)y * (size_t)width * 4;
        uint8_t* to = in.row(y);
        for (int x = 0; x < width; x++, from += 4, to += 3)
        {
            const int a = from[3];
            for (int k = 0; k < 3; k++) to[k] = (uint8_t)((from[k] * a + 255 * (255 - a) + 127) / 255);
        }
    }
    maxSide = std::min(picture::MAX_SIDE, std::max(picture::MIN_SIDE, maxSide));
    int w = 0, h = 0;
    picture::fitWithin(width, height, maxSide, w, h);
    const picture::Image small = picture::downscale(in, w, h);
    image::Coded coded;
    if (!image::encode(small, std::min<uint64_t>(maxBytes, picture::MAX_PICTURE_BYTES), coded, error))
    {
        if (error.empty()) error = "The picture can't be made that small";
        return {};
    }
    return coded.bytes;
}

std::vector<uint8_t> decodePicture(const uint8_t* bytes, size_t length, int maxSide, std::string& error)
{
    picture::Image image;
    if (bytes == nullptr || length == 0) return {};
    maxSide = std::min(picture::MAX_DECODE_SIDE, std::max(1, maxSide));
    // Told apart by the bytes, as the desktop does: a JPEG 2000 codestream,
    // or else a Cool-chic stream. Nothing else from the air is ever decoded.
    const bool ok = picture::isCodestream(bytes, length) ? image::decode(bytes, length, image, error, maxSide)
                                                         : image::coolchicDecode(bytes, length, image, error);
    if (!ok || image.empty()) return {};
    if (image.width > maxSide || image.height > maxSide || (image.channels != 1 && image.channels != 3))
    {
        error = "The picture is too big to show";
        return {};
    }
    std::vector<uint8_t> out(8 + (size_t)image.width * (size_t)image.height * 4);
    for (int k = 0; k < 4; k++)
    {
        out[k] = (uint8_t)((uint32_t)image.width >> (8 * k));
        out[4 + k] = (uint8_t)((uint32_t)image.height >> (8 * k));
    }
    uint8_t* to = out.data() + 8;
    for (int y = 0; y < image.height; y++)
    {
        const uint8_t* from = image.row(y);
        for (int x = 0; x < image.width; x++, from += image.channels, to += 4)
        {
            to[0] = from[0];
            to[1] = from[image.channels == 3 ? 1 : 0];
            to[2] = from[image.channels == 3 ? 2 : 0];
            to[3] = 255;
        }
    }
    return out;
}
} // namespace ragtime::android
