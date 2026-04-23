package com.core.kv.applications.printer;

import com.core.kv.KvConstants;
import com.core.infrastructure.encoding.NumberValueEncoder;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Field;
import com.core.platform.applications.printer.Printer;
import com.core.platform.bus.BusClient;
import org.agrona.MutableDirectBuffer;

/**
 * The {@code ClobPrinter} is an extension of {@code Printer} which adds fixed-point field formatters for fields named
 * {@code qty} or {@code price}.
 */
public class KvPrinter extends Printer {

    /**
     * Creates a printer application with the specified parameters and adds fixed-point field formatters for the
     * {@code qty} and {@code price} fields.
     *
     * @param logFactory a factory to create logs
     * @param busClient the bus client
     * @param logDirectory the log directory to write the printer to
     */
    public KvPrinter(LogFactory logFactory, BusClient<?, ?> busClient, String logDirectory) {
        super(logFactory, busClient, logDirectory);

        setFormatter("qty", (decoder, field, buffer, offset)
                -> writeFp(decoder, field, buffer, offset, KvConstants.QTY_ENCODER));
        setFormatter("price", (decoder, field, buffer, offset)
                -> writeFp(decoder, field, buffer, offset, KvConstants.PRICE_ENCODER));
    }

    private int writeFp(
            Decoder decoder, Field field, MutableDirectBuffer buffer, int offset, NumberValueEncoder encoder) {
        var position = offset;
        position += writeFieldName(buffer, position, field);
        position += encoder.encode(null, buffer, position, decoder.integerValue(field.getName()));
        return position - offset;
    }
}
