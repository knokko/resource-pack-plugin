package nl.knokko.resourcepack;

import java.io.OutputStream;

public class VoidOutputStream extends OutputStream {
	@Override
	public void write(int b) {}

	@Override
	public void write(byte[] bytes, int offset, int length) {}
}
