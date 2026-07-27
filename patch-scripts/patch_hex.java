    private String formatBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) return "";
        
        boolean isBinary = false;
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("image/") || ct.contains("application/octet-stream") || 
                ct.contains("application/pdf") || ct.contains("application/zip") ||
                ct.contains("audio/") || ct.contains("video/")) {
                isBinary = true;
            }
        }
        
        if (!isBinary) {
            int unprintable = 0;
            for (int i = 0; i < Math.min(body.length, 512); i++) {
                byte b = body[i];
                if (b == 0 || (b < 32 && b != '\n' && b != '\r' && b != '\t')) {
                    unprintable++;
                }
            }
            if (unprintable > 2) isBinary = true;
        }

        if (isBinary) {
            return "\n--- BINARY PAYLOAD (HEX DUMP) ---\n" + toHexDump(body);
        } else {
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            return "\n" + JsonParser.formatJsonString(text);
        }
    }

    private String toHexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int width = 16;
        for (int i = 0; i < data.length; i += width) {
            sb.append(String.format("%08x  ", i));
            for (int j = 0; j < width; j++) {
                if (i + j < data.length) {
                    sb.append(String.format("%02x ", data[i + j]));
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(" ");
            }
            sb.append(" |");
            for (int j = 0; j < width && i + j < data.length; j++) {
                char c = (char) data[i + j];
                if (c >= 32 && c <= 126) {
                    sb.append(c);
                } else {
                    sb.append('.');
                }
            }
            sb.append("|\n");
        }
        return sb.toString();
    }
