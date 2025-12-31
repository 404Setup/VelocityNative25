/*
 * Copyright (C) 2019-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.natives.compression;

import com.google.common.base.Preconditions;
import com.velocitypowered.natives.util.BufferPreference;
import io.netty.buffer.ByteBuf;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.zip.DataFormatException;

/**
 * Implements deflate compression using the {@code libdeflate} native library via Java FFM API.
 */
public class FfmapiVelocityCompressor implements VelocityCompressor {

  public static final VelocityCompressorFactory FACTORY = FfmapiVelocityCompressor::new;

  private static MethodHandle INFLATE_INIT;
  private static MethodHandle INFLATE_FREE;
  private static MethodHandle INFLATE_PROCESS;
  private static MethodHandle DEFLATE_INIT;
  private static MethodHandle DEFLATE_FREE;
  private static MethodHandle DEFLATE_PROCESS;

  static void init() {
    try {
      Linker linker = Linker.nativeLinker();
      SymbolLookup lookup = SymbolLookup.loaderLookup();

      if (INFLATE_INIT == null) {
        INFLATE_INIT = linker.downcallHandle(
          lookup.find("ffmapi_inflate_init").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS)
        );
      }

      if (INFLATE_FREE == null) {
        INFLATE_FREE = linker.downcallHandle(
          lookup.find("ffmapi_inflate_free").orElseThrow(),
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
      }

      // ffmapi_inflate_process returns int (0=Success, 1=NoSpace, 2=BadData, 3=Error)
      if (INFLATE_PROCESS == null) {
        INFLATE_PROCESS = linker.downcallHandle(
          lookup.find("ffmapi_inflate_process").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
      }

      if (DEFLATE_INIT == null) {
        DEFLATE_INIT = linker.downcallHandle(
          lookup.find("ffmapi_deflate_init").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
      }

      if (DEFLATE_FREE == null) {
        DEFLATE_FREE = linker.downcallHandle(
          lookup.find("ffmapi_deflate_free").orElseThrow(),
          FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
      }

      if (DEFLATE_PROCESS == null) {
        DEFLATE_PROCESS = linker.downcallHandle(
          lookup.find("recastxz_zlib_deflate_process").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );
      }
    } catch (Throwable e) {
      throw new RuntimeException("Failed to initialize FFM natives", e);
    }
  }

  private final MemorySegment inflateCtx;
  private final MemorySegment deflateCtx;
  private boolean disposed = false;

  private FfmapiVelocityCompressor(int level) {
    init();
    int correctedLevel = level == -1 ? 6 : level;
    if (correctedLevel > 12 || correctedLevel < 1) {
      throw new IllegalArgumentException("Invalid compression level " + level);
    }

    MemorySegment tempInflate = MemorySegment.NULL;
    MemorySegment tempDeflate = MemorySegment.NULL;

    try {
      tempInflate = (MemorySegment) INFLATE_INIT.invokeExact();
      if (tempInflate.equals(MemorySegment.NULL)) {
        throw new RuntimeException("Failed to initialize inflate context");
      }

      tempDeflate = (MemorySegment) DEFLATE_INIT.invokeExact(correctedLevel);
      if (tempDeflate.equals(MemorySegment.NULL)) {
        throw new RuntimeException("Failed to initialize deflate context");
      }
    } catch (Throwable e) {
      if (!MemorySegment.NULL.equals(tempInflate)) {
        try {
          INFLATE_FREE.invokeExact(tempInflate);
        } catch (Throwable ignored) {
        }
      }
      throw new RuntimeException("Error invoking native initialization", e);
    }

    this.inflateCtx = tempInflate;
    this.deflateCtx = tempDeflate;
  }

  @Override
  public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
      throws DataFormatException {
    ensureNotDisposed();

    destination.ensureWritable(uncompressedSize);

    long sourceAddress = source.memoryAddress() + source.readerIndex();
    long destinationAddress = destination.memoryAddress() + destination.writerIndex();

    try {
      int result = (int) INFLATE_PROCESS.invokeExact(
          inflateCtx,
          MemorySegment.ofAddress(sourceAddress),
          source.readableBytes(),
          MemorySegment.ofAddress(destinationAddress),
          uncompressedSize
      );

      switch (result) {
        case 0: // Success
          destination.writerIndex(destination.writerIndex() + uncompressedSize);
          break;
        case 1: // InsufficientSpace
          throw new DataFormatException("Destination buffer too small");
        case 2: // BadData
          throw new DataFormatException("Invalid compressed data");
        default: // Error (3)
          throw new DataFormatException("Failed to decompress data");
      }
    } catch (DataFormatException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException("FFM inflate invocation failed", e);
    }
  }

  @Override
  public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
    ensureNotDisposed();

    while (true) {
      long sourceAddress = source.memoryAddress() + source.readerIndex();
      long destinationAddress = destination.memoryAddress() + destination.writerIndex();

      try {
        int produced = (int) DEFLATE_PROCESS.invokeExact(
            deflateCtx,
            MemorySegment.ofAddress(sourceAddress),
            source.readableBytes(),
            MemorySegment.ofAddress(destinationAddress),
            destination.writableBytes()
        );

        if (produced > 0) {
          destination.writerIndex(destination.writerIndex() + produced);
          break;
        } else if (produced == 0) {
          // Insufficient room - enlarge the buffer.
          destination.capacity(destination.capacity() * 2);
        } else {
          throw new DataFormatException("libdeflate returned unknown code " + produced);
        }
      } catch (DataFormatException e) {
        throw e;
      } catch (Throwable e) {
        throw new RuntimeException("FFM deflate invocation failed", e);
      }
    }
  }

  private void ensureNotDisposed() {
    Preconditions.checkState(!disposed, "Object already disposed");
  }

  @Override
  public void close() {
    if (!disposed) {
      try {INFLATE_FREE.invokeExact(inflateCtx);} catch (Throwable _) {}
      try {DEFLATE_FREE.invokeExact(deflateCtx);} catch (Throwable _) {}
      disposed = true;
    }
  }

  @Override
  public BufferPreference preferredBufferType() {
    return BufferPreference.DIRECT_REQUIRED;
  }
}