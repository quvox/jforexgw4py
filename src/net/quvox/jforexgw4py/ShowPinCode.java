package net.quvox.jforexgw4py;

import java.awt.Canvas;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;


public class ShowPinCode implements ActionListener {

	private JFrame frame;
	private String pin = null;
	TextField  textbox = null;
	Socket client;
	DataOutputStream output;

	public ShowPinCode(BufferedImage img, int port) {
		frame = new JFrame();
		frame.setTitle("PINコードを入力してください");
		frame.setBounds( 0, 0, 330, 230);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		Container container = frame.getContentPane();
		container.setLayout(null);
		container.setBounds(0, 0, 330, 220);

		JPanel panel1 = new JPanel(new GridLayout(1,1));
		panel1.setBounds(0, 0, 330, 130);
		PinCanvas canvas = new PinCanvas(img);
		panel1.add(canvas);
		container.add(panel1);

		JPanel panel2 = new JPanel(new GridLayout(2,1));
		panel2.setBounds(0, 130, 330, 50);
		textbox = new TextField();
		JButton button = new JButton("OK");
		panel2.add(textbox);
		panel2.add(button);
		container.add(panel2);

		button.addActionListener(this);

		frame.setVisible(true);
		try {
			client = new Socket("localhost", port);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			output = new DataOutputStream(client.getOutputStream());
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public String waitPinCode() {

		return JOptionPane.showInputDialog(frame, "Message");
	}

	private class PinCanvas extends Canvas {
		private static final long serialVersionUID = 1L;
		Image image = null;
		public PinCanvas(Image img) {
			super();
			image = img;
		}

		public void paint(Graphics g){
			Graphics2D g2 = (Graphics2D)g;
			g2.drawImage(image, (this.getWidth()-image.getWidth(null))/2, 0, this);
		}
	}

	@Override
	public void actionPerformed(ActionEvent arg0) {
		pin = textbox.getText();
		try {
			output.writeBytes(pin);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			output.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			client.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		frame.dispose();
	}

}
